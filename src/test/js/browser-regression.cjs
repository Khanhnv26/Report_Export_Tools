const { chromium } = require(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const http = require('node:http');
const root = path.resolve(__dirname,'../../main/resources/static');
const output = path.resolve(__dirname,'../../..','target/ui-check');
async function main() {
  await fs.mkdir(output,{recursive:true});
  const server = http.createServer(async (request,response) => {
    const file = path.resolve(root,'.'+new URL(request.url,'http://localhost').pathname.replace(/\/$/,'/index.html'));
    if (!file.startsWith(root+path.sep)) { response.writeHead(404).end(); return; }
    try { response.setHeader('Content-Type',file.endsWith('.js')?'text/javascript':file.endsWith('.css')?'text/css':'text/html'); response.end(await fs.readFile(file)); }
    catch (_) { response.writeHead(404).end(); }
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  let browser;
  try {
    browser = await chromium.launch({headless:true,executablePath:process.env.REPORT_TEST_CHROMIUM});
    const page = await browser.newPage({viewport:{width:1440,height:1000}});
    page.setDefaultTimeout(10000);
    const errors=[]; page.on('pageerror',error=>errors.push(error.message)); page.on('dialog',dialog=>{errors.push('Unexpected native '+dialog.type());dialog.dismiss();});
    let previewError=false;
    let submitted;
    await page.route('**/api/**',async route=>{
      const url=new URL(route.request().url()); const body=route.request().postDataJSON(); let data;
      if(url.pathname==='/api/database-types') data=[{id:'MYSQL',name:'MySQL',defaultPort:3306,databaseLabel:'Database',supportsSchema:false},{id:'ORACLE',name:'Oracle',defaultPort:1521,databaseLabel:'Service name',supportsSchema:true}];
      else if(url.pathname==='/api/databases' && route.request().method()==='GET') data=[{id:'demo',name:'Đơn hàng',host:'localhost',port:3306,databaseName:'orders',dbType:'MYSQL',connected:true}];
      else if(url.pathname==='/api/databases' && route.request().method()==='POST') {await route.fulfill({status:400,json:{message:'Không kết nối được database thử nghiệm.'}});return;}
      else if(url.pathname.endsWith('/columns')) data=[{name:'id',type:'BIGINT'},{name:'amount',type:'DECIMAL'},{name:'created_at',type:'TIMESTAMP'}];
      else if(url.pathname.endsWith('/tables')) data=['orders'];
      else if(url.pathname.includes('preview-details')) {
        submitted=body;
        if(previewError){await route.fulfill({status:400,json:{message:'Database thử nghiệm không phản hồi.'}});return;}
        data={columns:body.outputColumns,rows:[body.outputColumns.map(col=>({'orders.id':'9223372036854775807','orders.amount':'1.234,50','orders.created_at':'06/11/2026 09:30:00'}[col.field]))],warnings:[],sampled:!body.filters.length&&!body.sortField,processedRows:1,elapsedMillis:24};
      } else if(url.pathname==='/api/reports/jobs') data={id:'test-job',state:'QUEUED',message:'Đang chờ đến lượt…',rowCount:0,warnings:[]};
      else if(url.pathname.endsWith('/jobs/test-job')) data=route.request().method()==='DELETE'?{id:'test-job',state:'CANCELLED',message:'Đã hủy báo cáo.',rowCount:0,warnings:[]}:{id:'test-job',state:'READY',message:'File đã sẵn sàng.',rowCount:1,warnings:['1 dòng kết quả không có dữ liệu từ nguồn CRM.']};
      else {await route.fulfill({status:404,json:{message:'Unknown fixture route'}});return;}
      await route.fulfill({json:{status:200,data}});
    });
    const origin='http://127.0.0.1:'+server.address().port;
    await page.goto(origin); await page.getByRole('heading',{name:'Nguồn dữ liệu',exact:true}).waitFor();
    await page.screenshot({path:path.join(output,'initial.png'),fullPage:true});
    await page.getByLabel(/^Database/).selectOption('demo');
    await page.getByLabel(/^Bảng/).selectOption('orders');
    await page.getByText('3 đã chọn').waitFor();
    await page.getByRole('button',{name:'Tiếp: Cột đầu ra'}).click();
    await page.getByLabel('Tiêu đề cột 1',{exact:true}).fill('Mã đơn hàng');
    await page.getByLabel('Kiểu dữ liệu cột 1',{exact:true}).selectOption('TEXT');
    await page.getByRole('button',{name:'Đưa cột amount lên',exact:true}).click();
    await page.screenshot({path:path.join(output,'output-columns.png'),fullPage:true});
    await page.getByRole('button',{name:'Tiếp: Lọc & sắp xếp'}).click();
    await page.getByLabel('Theo trường').selectOption('orders.amount');
    await page.getByRole('button',{name:'Tiếp: Xem trước & xuất'}).click();
    await page.getByRole('button',{name:'Tạo preview',exact:true}).first().click();
    await page.getByRole('cell',{name:'9223372036854775807',exact:true}).waitFor();
    assert.equal(submitted.outputColumns[0].field,'orders.amount'); assert.equal(submitted.sortField,'orders.amount');
    await page.screenshot({path:path.join(output,'preview-desktop.png'),fullPage:true});
    await page.getByRole('button',{name:'Tạo file Excel',exact:true}).click();
    await page.getByRole('button',{name:'Tải file Excel',exact:true}).waitFor();
    await page.getByRole('button',{name:'Tải file Excel',exact:true}).click();
    await page.getByRole('dialog').getByRole('heading',{name:'Kiểm tra cảnh báo trước khi tải'}).waitFor();
    await page.keyboard.press('Escape');
    await page.getByRole('button',{name:'Cột đầu ra',exact:false}).first().click();
    await page.getByLabel('Tiêu đề cột 1',{exact:true}).fill('Thành tiền');
    await page.getByRole('button',{name:'Xem trước & xuất',exact:false}).first().click();
    await page.getByText('Cấu hình đã thay đổi.',{exact:false}).waitFor();
    assert.equal(await page.getByRole('button',{name:'Tạo file Excel',exact:true}).isDisabled(),true);
    previewError=true; await page.getByRole('button',{name:'Cập nhật preview',exact:true}).click();
    await page.getByRole('dialog').getByText('Database thử nghiệm không phản hồi.').waitFor(); await page.keyboard.press('Escape'); previewError=false;
    await page.getByRole('button',{name:'Lưu mẫu báo cáo',exact:true}).click();
    await page.getByRole('dialog').getByLabel('Tên mẫu').fill('Báo cáo doanh thu');
    await page.getByRole('dialog').getByRole('button',{name:'Lưu mẫu',exact:true}).click();
    await page.getByText('Đã lưu mẫu “Báo cáo doanh thu”.',{exact:true}).waitFor();
    await page.getByRole('button',{name:'Thiết lập nguồn',exact:true}).click();
    await page.getByRole('button',{name:'Thêm kết nối',exact:true}).click();
    await page.getByRole('dialog').getByRole('button',{name:'Kiểm tra & lưu kết nối'}).click();
    await page.getByText('Nhập tên gợi nhớ.',{exact:true}).waitFor();
    await page.getByLabel('Loại database',{exact:true}).selectOption('ORACLE');
    assert.equal(await page.getByLabel('Cổng',{exact:true}).inputValue(),'1521');
    await page.getByLabel('Service name',{exact:true}).fill('orclpdb');
    await page.screenshot({path:path.join(output,'connection-dialog.png'),fullPage:true});
    await page.keyboard.press('Escape');
    await page.getByRole('button',{name:'Về báo cáo'}).click();
    await page.setViewportSize({width:390,height:844});
    await page.screenshot({path:path.join(output,'preview-mobile.png'),fullPage:true});
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth),true,'mobile layout must not overflow the page');
    assert.deepEqual(errors,[]);
    console.log('PASS: desktop/mobile layout, mapping/reorder, preview, stale state, background export, custom errors/confirm/input, Oracle form, no native dialogs.');
    console.log('Screenshots: '+output);
  } finally {if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));}
}
main().catch(error=>{console.error(error);process.exitCode=1;});
