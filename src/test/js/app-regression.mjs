import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
const source = await readFile(new URL('../../main/resources/static/app.js', import.meta.url), 'utf8');
let definition;
let pendingPreview;
let metadataCalls = 0;
const store = new Map();
const sandbox = {
  Vue: { createApp(value) { definition = value; return { mount() {} }; } },
  document: { activeElement: { focus() {} }, querySelector() { return null; } },
  localStorage: { getItem: key => store.get(key) || null, setItem: (key,value) => store.set(key,value), removeItem: key => store.delete(key) },
  AbortController, setTimeout: () => 0, clearTimeout() {},
  fetch: async (url, options) => {
    if (url.includes('preview-details')) return new Promise(resolve => { pendingPreview = data => resolve({ ok:true, json:async()=>({data}) }); });
    if (url.endsWith('/columns')) metadataCalls++;
    return { ok:true, json:async()=>({ data:
      url === '/api/database-types' ? [{id:'MYSQL',defaultPort:3306,databaseLabel:'Database',supportsSchema:false}, {id:'ORACLE',defaultPort:1521,databaseLabel:'Service name',supportsSchema:true}] :
      url.includes('/api/templates/') ? [{letter:'A',title:'name',index:0}] :
      url.endsWith('/columns') ? [{name:'id',type:'BIGINT'},{name:'name',type:'VARCHAR'}] : ['users'] }) };
  }
};
vm.runInNewContext(source,sandbox);
const app = definition.data(); app.$nextTick = fn => fn();
for (const [name,method] of Object.entries(definition.methods)) app[name] = method.bind(app);
for (const [name,get] of Object.entries(definition.computed)) Object.defineProperty(app,name,{get:()=>get.call(app)});
await app.fetchDatabaseTypes(); app.openNewDatabase(); app.newDb.dbType='ORACLE'; app.onDbTypeChange();
assert.equal(app.newDb.port,1521); assert.equal(app.selectedDatabaseType.databaseLabel,'Service name');
app.config.baseTable={dbId:'orders',tableName:'users',alias:'owner',columns:['id','name'],filterColumn:'',fromDate:'',toDate:''};
const join={dbId:'crm',tableName:'users',alias:'buyer',columns:[]}; app.config.joins=[join];
await app.onJoinTableChange(join); await app.onJoinTableChange(join);
assert.deepEqual(Array.from(join.columns),['id','name']); assert.equal(metadataCalls,1);
app.config.joins=[]; app.syncOutputColumns();
assert.deepEqual(Array.from(app.config.outputColumns,column=>column.field),['owner.id','owner.name']);
app.moveColumn(1,0); assert.equal(app.config.outputColumns[0].field,'owner.name');
const before = app.currentSignature;
const loading = app.fetchPreview();
app.config.outputColumns[0].title='New title';
pendingPreview({columns:[],rows:[['stale']],warnings:[]}); await loading;
assert.equal(app.preview,null,'response from an older config must not replace preview');
app.preview={columns:[],rows:[],warnings:[]}; app.previewSignature=before;
assert.equal(app.previewStale,true); assert.equal(app.canExport,false);
const confirm = app.ask('Delete?','Description'); assert.equal(app.dialog.kind,'confirm'); app.closeDialog(false); assert.equal(await confirm,false);
const input = app.ask('Save','Description',{input:true}); app.dialog.value='  '; app.closeDialog(true); assert.ok(app.dialog.error);
app.dialog.value='My report'; app.closeDialog(true); assert.equal(await input,'My report');
app.savedProfiles.legacy={templateId:'sample.xlsx',headerRow:0,baseTable:app.config.baseTable,joins:[],mapping:{A:'owner.name'}};
app.selectedProfile='legacy'; await app.loadProfile();
assert.equal(app.config.outputMode,'TEMPLATE'); assert.equal(app.config.outputColumns[0].field,'owner.name');
assert.equal(app.config.outputColumns[0].letter,'A');
const deleteCancel = app.deleteProfile('legacy');
assert.equal(app.dialog.kind, 'confirm');
app.closeDialog(false);
await deleteCancel;
assert.ok(app.savedProfiles.legacy);
const deleteConfirm = app.deleteProfile('legacy');
app.closeDialog(true);
await deleteConfirm;
assert.equal(app.savedProfiles.legacy, undefined);
assert.equal(app.selectedProfile, '');
assert.doesNotMatch(source,/\b(?:alert|confirm|prompt)\s*\(/);
assert.doesNotMatch(source,/\bvar\s+/);
console.log('PASS: catalog, metadata cache, output order, stale-preview protection, custom dialogs, legacy profiles, no window dialogs or var.');
