# Microservice Report Tool

Tool đọc dữ liệu từ PostgreSQL, MySQL, SQL Server và Oracle, ghép theo khóa tại tầng ứng dụng rồi xuất vào file Excel mẫu `.xlsx`.

## Database được hỗ trợ

- MySQL: cổng mặc định 3306, chọn database; không cần schema riêng.
- PostgreSQL: cổng 5432, chọn database và schema tùy chọn.
- SQL Server / MSSQL: cổng 1433, chọn database và schema tùy chọn.
- Oracle: cổng 1521; nhập **service name** vào ô tương ứng (ví dụ `orclpdb.company.local`), không nhập SID hay toàn bộ JDBC URL. Schema tùy chọn; bỏ trống sẽ dùng schema hiện tại của tài khoản.

Mỗi kết nối gắn với một schema, phần đọc metadata và truy vấn dùng cùng schema đó. Muốn ghép hai schema, tạo hai kết nối và đặt alias riêng cho các bảng trong báo cáo. Tên schema, bảng và cột dùng đúng chữ hoa/thường theo metadata. Hỗ trợ tên chứa chữ Unicode, chữ số, khoảng trắng, `_`, `$`, `#`, `-`; tên có dấu chấm hoặc dấu ngoặc/nháy chưa được hỗ trợ. Các identifier được quote theo từng database để xử lý cột trùng từ khóa.

Danh sách database, nhãn kết nối và cổng mặc định lấy từ `GET /api/database-types`; giao diện không khai báo danh sách riêng. Driver của cả bốn loại được đóng gói trong JAR. Adapter Oracle dùng cú pháp service name theo [tài liệu Oracle JDBC](https://docs.oracle.com/en/database/oracle/oracle-database/18/jjdbc/data-sources-and-URLs.html).

Các cột CLOB được đọc thành text, BLOB/binary thành Base64 ngay khi connection còn mở; giới hạn 1 MiB mỗi giá trị CLOB/BLOB/binary. Array, UUID, JSON và các giá trị đặc thù khác được xuất dạng text. Các chức năng SID/TNS/Oracle Wallet, MongoDB, Redis và tải JDBC driver tùy ý chưa được triển khai.

## Thêm loại database mới

1. Viết một adapter trong `src/main/java/com/report/tool/database` thực thi `DatabaseAdapter`: khai báo loại/cổng/driver, URL kết nối, quote identifier, giới hạn dòng và phạm vi metadata. Có thể tùy chỉnh cách đọc giá trị JDBC và kích thước lô khóa.
2. Đăng ký adapter trong `DatabaseAdapters` và khai báo JDBC driver trong `pom.xml`.
3. Bổ sung kiểm thử driver, truy vấn có schema/từ khóa, lọc ngày và ghép dữ liệu. Không cần sửa phần ghép báo cáo hoặc danh sách lựa chọn trên giao diện.

## Chạy trên máy cá nhân

Yêu cầu JDK 21 và Maven. Đặt `JAVA_HOME` trỏ đến JDK 21 nếu máy có nhiều bản Java.

```powershell
mvn clean package
java -jar target/microservice-report-tool-1.0.0.jar
```

Mở `http://localhost:8080`. Mặc định ứng dụng chỉ lắng nghe tại `127.0.0.1`.

1. Thêm kết nối bằng tài khoản database chỉ có quyền `SELECT` trên các bảng/cột báo cáo cần dùng. Quyền truy cập dữ liệu được giới hạn bằng tài khoản database; ứng dụng hiện là công cụ dành cho một quản trị viên.
2. Upload template `.xlsx`, chọn dòng tiêu đề (bắt đầu từ **0**).
3. Chọn bảng gốc và các bảng phụ; đặt alias riêng cho mỗi nguồn, ví dụ `orders`, `crm_users`, `billing_users`.
4. Nhập khóa nguồn, khóa đích và ánh xạ các trường sang cột Excel. Khóa nguồn có thể là `customer_id`, `orders.customer_id` hoặc `crm_users.manager_id` từ một bảng đã ghép trước đó.
5. Xem trước rồi xuất Excel. Có thể lưu cấu hình trên trình duyệt và nạp lại ở lần sau.

## Quy tắc dữ liệu

- Ghép theo **LEFT JOIN**: không tìm thấy dữ liệu phụ vẫn giữ dòng gốc và để trống cột phụ. Một khóa khớp nhiều bản ghi tạo nhiều dòng kết quả. Khi ghép nhiều quan hệ một–nhiều, số dòng có thể nhân lên; tool không tự tính SUM/COUNT. Cần xác định cấp chi tiết của báo cáo trước khi cộng tiền trên kết quả.
- Alias phải khác nhau; nếu bỏ trống thì dùng tên bảng. Profile cũ có tên bảng trùng nhau cần được đặt alias và ánh xạ lại.
- Lọc ngày nhận `yyyy-MM-dd`, bao gồm toàn bộ ngày kết thúc; dùng mốc đầu ngày hôm sau làm cận trên loại trừ. Ngày được hiểu theo kiểu dữ liệu và múi giờ của phiên database; chưa có chuyển đổi múi giờ chung giữa các service.
- Preview đọc tối đa 20 dòng gốc ngay từ database và trả tối đa 20 dòng đã ghép. Preview không có thứ tự cố định và không đảm bảo trùng với dữ liệu lúc export nếu database đã thay đổi.
- Mặc định tối đa **50.000 dòng** ở bảng gốc, dữ liệu phụ được lấy cho mỗi bước ghép, và kết quả của mỗi bước ghép. Vượt giới hạn sẽ báo lỗi, không xuất file bị cắt mất dòng. Điều chỉnh bằng `REPORT_MAX_ROWS` (20–1.000.000) theo bộ nhớ máy. Dữ liệu vẫn được xử lý trong RAM; đây chưa phải cơ chế streaming cho hàng triệu dòng.
- Tối đa hai truy vấn báo cáo và hai lượt xuất file đồng thời; khi bận API trả HTTP 429.
- Số vượt 15 chữ số hoặc không thể chuyển an toàn sang số Excel được xuất thành **text**, kể cả ID dài và số thập phân độ chính xác cao. Các số thông thường vẫn là ô số. Preview cũng giữ ID dài dưới dạng chuỗi để trình duyệt không làm tròn.
- Chỉ ghi dữ liệu vào sheet đầu tiên của template. Nên dùng template trống phần dữ liệu, không chứa các dòng dữ liệu cũ cần xóa hoặc footer cần dịch chuyển. Công thức, tổng hợp và định dạng mở rộng nhiều dòng chưa được tự động nhân bản.

## Mật khẩu và kết nối lại

`connections.json` chỉ lưu thông tin kết nối, **không lưu mật khẩu database**. Nếu có file cũ chứa mật khẩu, lần khởi động tiếp theo sẽ loại mật khẩu khỏi file trước khi kết nối lại. Không commit file này.

Sau khi khởi động lại, bấm **Kết nối lại** để nhập mật khẩu; ID kết nối được giữ nguyên nên profile vẫn dùng được. Biểu tượng màu vàng là kết nối chưa hoạt động. Để tự kết nối lại, cung cấp mật khẩu qua biến môi trường theo ID kết nối trong `connections.json`: `REPORT_DB_PASSWORD_<ID_VIẾT_HOA>`, thay dấu `-` trong ID bằng `_`. Ví dụ ID `db_abc123` dùng biến `REPORT_DB_PASSWORD_DB_ABC123`. Quản lý giá trị biến bằng cơ chế bí mật của môi trường triển khai, không ghi mật khẩu vào repo.

## Chạy qua mạng

Khi cần chia sẻ, đặt `REPORT_TOOL_BIND_ADDRESS`, `REPORT_TOOL_USERNAME` (mặc định `report`) và **bắt buộc** đặt `REPORT_TOOL_PASSWORD` bằng mật khẩu riêng. Trình duyệt sẽ hiện hộp đăng nhập HTTP Basic. Không có mật khẩu, ứng dụng từ chối khởi động khi bind ra mạng. Khi dùng qua mạng, triển khai HTTPS và giới hạn mạng truy cập; xác thực hiện dùng chung một tài khoản quản trị, chưa có phân quyền theo người dùng.

Ví dụ các giá trị không phải bí mật có thể đặt trong PowerShell:

```powershell
$env:REPORT_TOOL_BIND_ADDRESS = '0.0.0.0'
$env:REPORT_TOOL_USERNAME = 'report'
# Cấp REPORT_TOOL_PASSWORD từ cơ chế quản lý bí mật trước khi chạy.
java -jar target/microservice-report-tool-1.0.0.jar
```

Có thể tắt tự mở trình duyệt bằng `--report.open-browser=false`; đổi cổng bằng `--server.port=8081`. Các kết nối database dùng cấu hình TLS trong adapter tương ứng; cần điều chỉnh theo chứng thư và chính sách của môi trường triển khai trước khi dùng trên mạng không tin cậy.

## Kiểm thử

```powershell
mvn test
node src/test/js/app-regression.mjs
```

Kiểm thử Java nạp JDBC driver thật và kiểm tra URL của cả bốn loại; dùng H2 ở chế độ tương thích PostgreSQL, MySQL, SQL Server và Oracle để kiểm tra truy vấn, ghép dữ liệu, schema, lọc ngày, giới hạn dòng, API upload/preview/export, độ chính xác Excel và kiểm soát truy cập. Kiểm thử JavaScript chạy logic frontend với response giả lập. Đây không thay thế kiểm thử tích hợp với các database thật của công ty.
