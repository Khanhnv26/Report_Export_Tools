# Sử dụng giao diện báo cáo

## Chạy bản mới

Yêu cầu JDK 21. Sau khi build bằng `mvn package`, chạy:

```powershell
java -jar target/microservice-report-tool-1.0.0.jar
```

Mở `http://localhost:8080`. Vue và CSS đã được đóng gói cục bộ; không cần CDN hoặc Node.js để chạy ứng dụng.

## Luồng thao tác

Trong **Thiết lập nguồn**, người phụ trách kỹ thuật thêm hoặc kết nối lại database. Đây là cách phân chia công việc trong giao diện; ứng dụng vẫn dùng cơ chế truy cập một tài khoản quản trị hiện tại.

Trong **Báo cáo**, chọn mẫu đã lưu hoặc thực hiện bốn bước:

1. **Nguồn dữ liệu:** chọn bảng gốc và cột cần đọc. Thêm nguồn ghép, chọn khóa từ danh sách và đặt alias riêng nếu cần. Quan hệ một–nhiều tạo thêm dòng; không khớp vẫn giữ dòng gốc.
2. **Cột đầu ra:** xuất bảng Excel mới hoặc điền template. Bảng mới hỗ trợ đổi tiêu đề, kéo dòng / nút lên xuống để đổi thứ tự, bỏ cột, chọn kiểu và định dạng. Với template, tiêu đề và vị trí cột theo sheet đầu tiên; cột không ánh xạ được bỏ qua.
3. **Lọc & sắp xếp:** bộ lọc ngày chạy tại database và bao gồm hết ngày kết thúc. Điều kiện sau ghép kết hợp bằng VÀ. So sánh số dùng giá trị số; so sánh chữ không phân biệt hoa/thường. Các điều kiện trên ngày dạng chữ nên dùng định dạng ISO. Chưa có SUM/COUNT hoặc GROUP BY.
4. **Xem trước & xuất:** tạo preview, kiểm tra cảnh báo rồi tạo file. Tác vụ xuất chạy nền, có trạng thái từng giai đoạn, hủy và tải file khi xong. Khi có cảnh báo không khớp nguồn phụ, cần xác nhận trước khi tải.

Preview hiển thị tối đa 20 dòng, dùng cùng bộ chuyển kiểu và định dạng với Excel. Khi lọc/sắp xếp sau ghép, backend xử lý đầy đủ dữ liệu trong giới hạn báo cáo để preview không lọc nhầm chỉ trên 20 dòng đầu. Các trường hợp còn lại đọc tối đa 20 dòng gốc. Bảng preview thể hiện các cột đã chọn, không mô phỏng ô gộp, công thức hay bố cục trang in.

Sửa cấu hình làm preview hết hiệu lực và khóa nút tạo file cho đến khi cập nhật. Kết quả request cũ không ghi đè cấu hình mới. Preview và export đọc dữ liệu ở hai thời điểm; không có snapshot chung giữa các database.

## Kiểu và định dạng

- **Tự động:** giữ kiểu thông thường, chuyển số có nguy cơ mất chính xác thành chữ.
- **Chữ:** phù hợp mã, ID dài, số điện thoại và dữ liệu cần giữ nguyên ký tự.
- **Số:** chuyển đổi nghiêm ngặt; dữ liệu sai hoặc vượt độ chính xác số Excel chặn xuất và nêu dòng/cột cần sửa.
- **Ngày / Ngày & giờ:** chấp nhận giá trị ngày JDBC, ISO và ngày `dd/MM/yyyy` hợp lệ.
- **Đúng / sai:** chấp nhận `true`, `false`, `1`, `0`.

Định dạng hiển thị gồm mặc định, số nguyên, hai số thập phân, tiền hai số lẻ, ngày và ngày giờ. Template giữ định dạng ô sẵn có khi để mặc định; các dòng thêm sử dụng kiểu ô từ dòng dữ liệu đầu tiên. Không tự nhân bản công thức/footer. Nên dùng template có vùng dữ liệu trống.

## Tác vụ và hiệu năng

- Giới hạn dữ liệu mặc định vẫn là 50.000 dòng cho bảng gốc, dữ liệu phụ và các bước ghép; vượt giới hạn báo lỗi thay vì cắt mất dữ liệu.
- Hai worker xuất nền, tối đa bốn tác vụ chờ; khi đầy trả HTTP 429. Phần đọc dữ liệu vẫn có giới hạn hai truy vấn báo cáo đồng thời.
- Bảng Excel mới dùng SXSSF, chỉ giữ cửa sổ 100 dòng workbook trong bộ nhớ. Dữ liệu ghép vẫn nằm trong RAM; template dùng workbook thông thường để giữ cấu trúc mẫu.
- Metadata được cache 60 giây và gộp request trùng. Không tự query preview mỗi lần gõ.
- Tải file qua endpoint download, không giữ toàn bộ file dưới dạng Blob trong trình duyệt.
- Hủy tác vụ gọi `Statement.cancel()` cho truy vấn đang chạy, đồng thời kiểm tra trạng thái hủy khi đọc, ghép và ghi. Thời gian dừng thực tế phụ thuộc JDBC driver và database.
- File ở thư mục `exports/`, tối đa 20 tác vụ được lưu trạng thái, hết hiệu lực sau một giờ không truy cập. Dọn file khi có truy cập hoặc khởi động; trạng thái tác vụ không được phục hồi sau khi ứng dụng khởi động lại. Thư mục này không nằm trong Git. Có thể đổi bằng `--report.output-directory=...`.

Phép đo dữ liệu giả 50.000 dòng × 3 cột trên máy phát triển: khoảng **1,09 giây**, file khoảng **1 MB**, chỉ gồm chuyển đổi đầu ra và ghi workbook. Chưa tính truy vấn database, đường truyền, ghép hoặc template; đây không phải cam kết thời gian cho dữ liệu thực tế.

## Thông báo và kiểm thử

### Tự kết nối lại khi mất phiên

- Mỗi preview/export có một lượt thử lại dùng chung cho toàn bộ truy vấn và lô ghép; mỗi request đọc bảng/cột có giới hạn riêng một lượt. Chờ khoảng 500 ms trước lần thử lại.
- Chỉ thử lại với lỗi kết nối đã nhận diện bằng kiểu lỗi JDBC, SQLState hoặc lỗi socket. Sai mật khẩu, mật khẩu hết hạn, thiếu quyền, sai SQL, nguồn không tồn tại, query timeout và hủy tác vụ không được thử lại. Mã lỗi chưa xác định mặc định không retry.
- Connection hỏng bị loại khỏi pool; connection mới dùng thông tin xác thực của phiên tool hiện tại. Không đóng toàn bộ pool, không lưu thêm mật khẩu và không tự đổi mật khẩu/token.
- Kết quả đọc dở bị bỏ. Chỉ truy vấn hoặc lô đang lỗi được đọc lại; các lô đã xong không chạy lại. Nếu vẫn mất kết nối hoặc lượt retry đã dùng hết, tác vụ dừng và không có file tải chưa hoàn chỉnh.
- Export có trạng thái “Đang kết nối lại”; thông tin phục hồi được giữ trên tác vụ sau khi thành công. Preview trả thông tin phục hồi cùng kết quả sau khi request hoàn tất. Nút hủy có hiệu lực cả khi chờ retry.
- Không có snapshot liên database: dữ liệu tại nguồn có thể thay đổi trong khoảng thử lại.

Quy tắc nhận diện dựa trên [SQLState PostgreSQL](https://www.postgresql.org/docs/current/errcodes-appendix.html) và hợp đồng [SQLRecoverableException của JDBC](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/SQLRecoverableException.html). Các lỗi cấu hình/xác thực đặc thù như [Oracle hết hạn mật khẩu](https://docs.oracle.com/en/error-help/db/ora-28001/) và [service không được listener nhận diện](https://docs.oracle.com/en/error-help/db/ora-12514/) được loại trừ theo mã lỗi.

Ứng dụng dùng toast, lỗi tại trường nhập và hộp thoại riêng. Không gọi `window.alert`, `window.confirm`, `window.prompt`. Hộp thoại hỗ trợ Escape, giữ focus trong hộp và trả focus khi đóng. Java dùng kiểu tường minh; JavaScript của ứng dụng dùng `const`/`let`.

```powershell
mvn test
node src/test/js/app-regression.mjs
# Chỉ cần Node.js/Playwright khi phát triển và kiểm thử giao diện:
npm install
npx playwright install chromium
npm run test:browser
```

Kiểm thử trình duyệt dùng dữ liệu giả và chụp ảnh desktop/mobile vào `target/ui-check`. Kiểm thử Java dùng H2 và các driver được đóng gói, kiểm tra cả xuất nền, hủy, cảnh báo, độ chính xác số và preview tương ứng workbook. Cần kiểm chứng thêm với database thật của công ty.
