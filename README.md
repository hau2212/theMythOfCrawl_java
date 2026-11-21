# theMythOf-JCrawl (Java / Montoya) - V_7.2_beta

Burp Suite extension viết bằng **Java 21 + Montoya API**: bản viết lại toàn bộ của
[theMythOf-PCrawl](https://github.com/hau2212/theMythOfCrawl) (bản Python / Jython) - đủ mọi chức năng,
nhanh hơn, có lưu dữ liệu ra đĩa (workspace), **không cần Jython**, và giao diện tự thích ứng cả
**Light / Dark theme** của Burp Suite (icon vẽ hoàn toàn bằng Java2D, không phụ thuộc emoji).

## Điểm mới V_7.2_beta

- **Cổng chặn Proxy gọn**: chọn lựa chọn nào thì mới hiện cấu hình của lựa chọn đó (host / host + path / nhánh; giải thích của
  đúng cách chặn đang chọn); phần Thử & luật khác thu gọn; vừa một màn hình, không tràn ngang.
- **Cuộn chuột nhanh** trong mọi cửa sổ và tab (khoảng 3 dòng mỗi nấc thay vì 1 px).
- **Action Workbench = màn hình nâng cao** của cùng danh sách luật mà nút "Chặn: …" và Bảo vệ dữ liệu dùng. Tab "CRUD" đổi tên thành
  **Danh sách luật (thêm / sửa / xóa)**; form tạo luật chỉ mở khi bấm **Tạo luật mới** hoặc chọn 1 luật để sửa (nút **Ẩn form** để thu lại).

## Điểm mới V_7.1_beta — Cổng chặn Proxy trực quan

![Cổng chặn Proxy](docs/images/v71-cong-chan-proxy.png)

Bấm nút **Chặn: ...** trên thanh trên cùng (hoặc menu *Bảo vệ dữ liệu → Cổng chặn Proxy*). Một công tắc + 3 câu hỏi:

| Bước | Hỏi | Lựa chọn |
|---|---|---|
| 1 | **Chặn request nào?** | Mọi host · Chỉ 1 host · 1 host + path (`/api/orders` = mọi path bên dưới, `*` = bất kỳ) · Chỉ khi đang crawl ở 1 nhánh |
| 2 | **Method nào?** | Tích chọn GET / POST / PUT / PATCH / DELETE / HEAD / OPTIONS (mặc định chỉ POST, PUT, PATCH, DELETE) |
| 3 | **Làm gì với request bị chặn?** | **Hỏi tôi trước** (dừng ở Burp, bạn bấm Gửi/Hủy) · **Giữ lại, trả 200 giả cho trình duyệt** · **Chặn hẳn** |

- **"200 giả" nghĩa là gì?** Response 200 giả được trả về cho **trình duyệt**, không phải cho Server: Server **không nhận gì**, trình duyệt tưởng
  thành công và chạy tiếp, request được lưu trong JCrawl để gửi tay / gửi theo role sau. Dùng khi crawl nút Xóa / Hủy / Thanh toán mà không muốn đổi dữ liệu thật.
- **Sơ đồ luồng** Trình duyệt → Burp + JCrawl → Server vẽ lại ngay khi bạn đổi lựa chọn; câu tóm tắt cho biết chính xác cái gì bị chặn, cái gì đi thẳng.
- **Thử với 1 request**: nhập method + URL → biết ngay request đó sẽ bị xử lý ra sao (không gửi gì).
- Công tắc trên thanh trên cùng: BẬT = dùng thiết lập đã lưu · TẮT = tắt mọi chặn, request đi thẳng.
- **Nút bấm luôn có phản hồi**: bấm nút cần chọn dòng trước (Lên / Xuống / Xóa / Bật-Tắt / Format JSON / Gửi / Repeater…) khi chưa chọn
  sẽ hiện nhắc "Chọn … trước"; nếu thao tác gặp lỗi, lỗi hiện ngay cạnh nút và ghi vào tab Cài đặt → Log (trước đây lỗi bị nuốt, trông như
  "bấm không ra gì"); mọi dialog luôn mở trên cửa sổ Burp đang dùng, không bị khuất phía sau.
- Dialog **Bảo vệ dữ liệu**: câu 1 trỏ về Cổng chặn; câu 2 (gửi lại khi ID đã bị xóa) có thẻ **Đầu vào → Đầu ra** cho từng cách
  (Tự động / Gửi nguyên bản / Thay ID còn sống / Tạo bản ghi mới rồi gửi / Dùng lại kết quả 200 cũ).

## Điểm mới V_7.0_beta

Kế hoạch & giải thích đầy đủ: [`docs/V7_PLAN.md`](docs/V7_PLAN.md).

### 1. Tab Cây dạng bảng thẳng hàng (tab mở mặc định)

![Tab Cây V_7.0](docs/images/v7-cay-tree-table.png)

- Nhánh vẫn thụt lề như cây, nhưng **mọi dòng API / role / [CHỜ DUYỆT] vẽ trên cùng một lưới cột**:
  `Nhánh / API | Method | Path | Status | Length | Kết quả Role | Đánh giá · Tag` — API ở nhánh sâu hay nông đều thẳng hàng.
- Kéo mép tiêu đề cột để đổi độ rộng (nhớ lại lần sau), nhấp đúp mép cột để về mặc định.
- `Ctrl+Alt+2` ẩn/hiện khung Request/Response dưới cây · `Ctrl+Alt+0` chỉ còn cây · ô **Crawl bằng** chọn role đang crawl.

### 2. Tab API kiểu Burp HTTP History

![Tab API V_7.0](docs/images/v7-api-light.png)

- Cột `# | Method | Host | Path | Status | Length | Kết quả Role | Nhánh | Đánh giá | Cách gửi lại | Time`; cột ít dùng ẩn mặc định (chuột phải header để bật).
- Cây bên trái chỉ còn nhánh + số đếm để lọc bảng; kéo dòng API thả vào nhánh để chuyển.
- `Ctrl+Alt+1/2/3/H` ẩn/hiện Cây / Chi tiết / Thanh công cụ / Thanh trên cùng · `Ctrl+Alt+0` chế độ Tối đa · nút **Bố cục ▾**.

### 3. Crawl N role không trùng & phát hiện BAC — tab **Ma trận Role**

![Ma trận Role](docs/images/v7-ma-tran-role.png)

- Extension **tự nhận ra role đang crawl** bằng cách so token / cookie của request với rule từng Role (hoặc chọn tay ở ô *Đang crawl bằng*).
- API đã có mà role khác cũng thấy → **không tạo bản trùng**, chỉ ghi thêm vào cột **Thấy bởi**.
- Gửi lại mọi API bằng mọi role → **Gọi được**. Role **không thấy** chức năng trong UI nhưng **gọi được 2xx cùng nội dung** (so độ giống body)
  → **NGHI BAC**. Khai báo **Quyền mong đợi** theo nhánh (ví dụ `Toppage > Admin` chỉ `01_Admin`) → **VI PHẠM** chắc chắn.
- Xuất **test case** (Excel 2 sheet / JSON) cho server quản lý.

### 4. Đẩy sang Logger++ / Xuất HAR

- Nút **Đẩy sang Logger++**: gửi lại mỗi API **1 lần** qua HTTP client của Burp (Logger++ bật log tool *Extensions* sẽ ghi request + response thật).
  Mặc định chỉ API gốc **2xx** và chỉ **GET/HEAD** (POST/PUT/PATCH/DELETE phải bật riêng + xác nhận). Tùy chọn header `X-JCrawl-Ref: API#..;role=..;branch=..`
  để lọc trong Logger++ (mặc định tắt để không để lại dấu vết lạ trên server mục tiêu).
- **Xuất HAR** (không gửi gì) kèm trường `_jcrawl` (API #, nhánh, Thấy bởi, role, đánh giá).

### 5. Làm việc nhóm an toàn với SOC

| Cách | Mạng từ Burp | Ghi chú |
|---|---|---|
| **Thư mục chung** (khuyến nghị) — OneDrive / SharePoint / Google Drive / ổ mạng | **Không** | Mỗi người ghi `jcrawl-team/<tên>.jsonl`, đọc file của người khác mỗi 3 giây; tự nối lại khi mở Burp |
| **Excel nhóm** 6 sheet (Tổng hợp, Ma trận Role, Test case & Bug, Phân công, Xung đột, Theo thành viên) | **Không** | Mỗi người xuất 1 file, một người **Gộp** lại; phát hiện xung đột kiểu Git |
| Google Sheets (đã có) | HTTPS tới Google | Giới hạn Web App trong tổ chức |
| Phòng LAN | Mở cổng | Mặc định chỉ `127.0.0.1`, bắt buộc passcode ≥ 8 ký tự; mở LAN phải bật riêng có cảnh báo |

Chỉ chia sẻ metadata (method, host, path, trạng thái, tag, người tìm) — không bao giờ chia sẻ token / cookie.

### 6. Bảo vệ dữ liệu (thay cho mục Action khó hiểu — từ V_7.1 câu 1 là Cổng chặn Proxy)

![Bảo vệ dữ liệu](docs/images/v7-bao-ve-du-lieu.png)

- **Câu 1** — khi trình duyệt gửi POST/PUT/PATCH/DELETE: *Cho đi bình thường* / *Hỏi tôi trước* / *Giữ lại, không gửi (200 giả)*;
  thêm luật riêng cho method / nhánh / API. Luật hiển thị bằng câu tiếng Việt, đọc từ trên xuống.
- **Thử luật**: nhập method + path + nhánh → biết ngay request sẽ bị xử lý ra sao (không gửi gì).
- **Câu 2** — khi gửi lại mà ID đã bị xóa: *Gửi nguyên bản* / *Thay ID còn sống* / *Tạo bản ghi mới rồi gửi* / *Dùng lại kết quả 200 cũ* / *Tự động*;
  cột **Cách gửi lại** trong bảng API cho biết lần gửi gần nhất đã làm gì.
- Nút **Gộp vào danh sách luật** chuyển mọi công tắc chặn kiểu cũ (giữ ở Proxy, chặn chờ theo nhánh/API, luật Proxy cũ) thành luật — hành vi giữ nguyên, chỉ còn **một nơi** để xem.
  Workbench cũ vẫn ở mục **Nâng cao**.

### 7. Tab gọn hơn

`Cây · API · Ma trận Role · Báo cáo (Lịch sử, Excel) · Roles · Cần xử lý · Cài đặt (Cấu hình, Log)` — `Alt+1..7`.
Thanh trên cùng dùng chữ rõ nghĩa: `API 12 · 6 nhánh`, `Xử lý 7`, `Gửi 0`.

## Giao diện (Light & Dark)

| Tab API (FlatLaf Light) | Tab API (FlatLaf Dark) |
|---|---|
| ![Tab API - Light](docs/images/api-light.png) | ![Tab API - Dark](docs/images/api-dark.png) |

| Lịch sử trực quan - Heatmap theo Role (Dark) | Tab Roles & JWT Inspector (Light) |
|---|---|
| ![Tab Lịch sử - Dark](docs/images/history-cards-dark.png) | ![Tab Roles - Light](docs/images/roles-light.png) |

## Cài đặt - chỉ cần 1 file

1. Tải **`theMythOf-JCrawl.jar`**: [link tải trực tiếp](https://github.com/hau2212/theMythOf-JCrawl/releases/latest/download/theMythOf-JCrawl.jar)
   (hoặc mục **Releases** bên phải trang repo). GitHub tự build lại mỗi lần có commit mới trên `main`.
2. Để file vào **một thư mục riêng**, ví dụ `D:\Tools\JCrawl\theMythOf-JCrawl.jar`.
   Dữ liệu sẽ được lưu ngay cạnh file: `D:\Tools\JCrawl\theMythOfCrawl_workspace\`.
3. Burp → **Extensions → Installed → Add** → Extension type **Java** → chọn file `.jar` → Next.
   Tab **`theMythOf-JCrawl`** xuất hiện trên thanh tab của Burp.

Không cần cài Jython, Python hay thư viện nào khác. Cần Burp Suite bản mới (có sẵn Java 21; bản 2024 trở lên).

**Cập nhật bản mới:** tải jar mới, ghi đè đúng file cũ (cùng thư mục) → trong Burp, Ctrl + click ô
*Loaded* của extension để nạp lại. Workspace (dữ liệu đã bắt) giữ nguyên.

**Tự build** (cần JDK 21): `gradlew.bat build` (hoặc `./gradlew build` trên Linux/macOS) → file ở
`build/libs/theMythOf-JCrawl.jar` (khi chạy từ `build/libs`, workspace nằm ở thư mục gốc của repo).

### Gặp lỗi giao diện (mất màu / ô vuông emoji / vẫn thấy giao diện cũ)

1. Mở **Extensions → Installed → chọn `theMythOf-JCrawl` → Output**, kiểm tra dòng nạp:
   `theMythOf-JCrawl 2.0.0 loaded · LAF=... · dark=...`
2. Kiểm tra tên tab trên thanh tab Burp là **`theMythOf-JCrawl`**. Nếu không có dòng Output trên hoặc tên tab vẫn là tên cũ → Burp đang nạp file `.jar` cũ; hãy **Unload / Remove** extension cũ và **Add** lại file `theMythOf-JCrawl.jar` mới nhất.
3. Mở tab **Cấu hình → mục 8. Giới thiệu & chẩn đoán** (hoặc rê chuột vào chip trạng thái trên header) để xem phiên bản, LAF, màu `Panel.background` / `Table.background` / `Component.accentColor`, hệ số scale và cỡ chữ (`90% / 100% / 115%`); bấm **Copy thông tin chẩn đoán** nếu cần gửi kèm ảnh chụp màn hình.

## Dùng nhanh

1. Tab **Roles**: sửa các role mẫu (token / cookie / JWT của từng tài khoản test), tắt role không dùng bằng switch gạt.
   Thẻ **API gốc** ghim trên cùng áp rule trực tiếp lên request đã bắt (nếu bật).
2. Thanh trên cùng (2 hàng gọn): bấm vào **Đích** (dạng **Breadcrumb** tô màu từng cấp) hoặc mũi tên bên cạnh để chọn
   nhánh đích, ví dụ `Toppage > contact > button "send"` - mọi request mới sẽ vào nhánh này cho tới khi bạn đổi.
3. Bật nút gạt **Dừng / Capture** (có chấm đỏ nhấp nháy nhẹ khi đang bắt), duyệt web qua Proxy của Burp. Mỗi API được
   bắt sẽ nhân bản thành 1 API gốc + N role.
4. Gạt **CHỈ CRAWL / GỬI** sang **GỬI** (tự gửi mọi role theo hàng đợi chống 429 có thanh tiến độ mảnh), hoặc chọn API /
   nhánh rồi bấm **Gửi** (`Ctrl+Enter`). Màu trạng thái: xanh lá = OK, vàng hổ phách = role bị chặn / lệch quyền, cam =
   nghi bypass (Guest vẫn 2xx), đỏ = lỗi server, xanh dương = 3xx / chưa gửi, xám = chờ xác nhận vị trí.
5. Pill **Cần xử lý** (thanh trên cùng / tab **Audit**): xác nhận vị trí API mới, API quay về chỗ cũ, API trùng.
6. (Tùy chọn) Tab **Excel** → nạp danh sách API (`STT | PROTOCOL | PATH | POSITION`) để theo dõi độ phủ qua KPI **StatCard**;
   bấm icon Copy ở đầu dòng hoặc `Ctrl+Shift+C` để copy `STT. path`.
7. Xuất kết quả: tab **Lịch sử** → **Xuất** (Excel 7 sheet, báo cáo `.md`, ma trận JSON).

Mọi thao tác chính đều hiển thị thông báo nổi không chặn (**Toast trượt**) kèm nút **Hoàn tác** khi kéo thả di chuyển API, và có icon trợ giúp hình chữ `i` mở popover chi tiết.

## Các tab & điểm mới

| Tab | Nội dung & Cải tiến |
|---|---|
| **Thanh trên cùng** | Thiết kế 2 hàng (`<= 64 px`) vừa màn hình hẹp 1100 px: toggle **Dừng / Capture**, **CHỈ CRAWL / GỬI**, **Hàng đợi**, **Đích** dạng Breadcrumb từng cấp, pill **Cần xử lý**, tìm kiếm nhanh `Ctrl+K`, tùy chọn, tách cửa sổ, Workspace và dải chip thống kê bấm-lọc nhanh |
| **API** | Cây Sub-folder hỗ trợ **kéo thả API sang nhánh khác** (có Hoàn tác) và chip bấm trực tiếp Copy / Excel / Trùng; bảng API có **ô lọc nhanh (`Ctrl+F`)**, **dải chip lọc nhiều trạng thái cùng lúc**, **nhớ độ rộng / thứ tự / ẩn cột** (chuột phải lên header cột để chỉnh, key `tmoc.ui.columns.*`); bảng kết quả role kèm dải tổng hợp `OK / Lệch / Bypass / Lỗi` và luôn ghim hàng **GỐC** ở đầu khi sort; editor Pretty / Raw / Hex của Burp giữ nguyên bên dưới |
| **Lịch sử** | Báo cáo trực quan **HistoryReportView**: dải 8 **StatCard KPI** (nhánh, API, OK, Lệch, Bypass, Lỗi, Chưa gửi, % Excel) + 3 chế độ xem (**Thẻ** theo nhánh kèm ma trận ô vuông heatmap từng role — bấm để nhảy tới đúng API & Role ở tab API; **Ma trận** bảng Role × API tô màu trạng thái; **Văn bản** thuần để đọc/copy) |
| **Roles** | Thẻ **API gốc** ghim đầu; danh sách role dạng thẻ với avatar tròn 2 chữ cái, switch gạt và chip rule màu theo mode (`REPLACE`, `ADD`, `REMOVE`, `COOKIE_SET`, `COOKIE_REMOVE`); **JWT Inspector** tô màu cú pháp JSON header/payload kèm nhãn cảnh báo `exp` hết hạn / còn hạn |
| **Cấu hình** | 8 mục thẻ màu bố cục lưới 2 cột (tự chuyển 1 cột khi hẹp), ô tìm kiếm cài đặt ở trên cùng, placeholder + viền đỏ khi regex sai; **Mục 8** thêm chỉnh **cỡ chữ giao diện (`90% / 100% / 115%`)**, bật/tắt animation nhẹ (`<= 150 ms`) và copy thông tin chẩn đoán |
| **Log** | Cây tải lười nhánh → API → dòng log (giờ monospace + icon Java2D theo loại + tô màu dòng lỗi) kèm ô tìm kiếm và chip lọc **Bắt / Gửi / Lỗi / Di chuyển / Hệ thống** |
| **Excel** | Màn hình chờ **EmptyState** khi chưa nạp file; 4 thẻ KPI độ phủ + chip lọc nhanh + bảng timeline lịch sử các lần gặp (icon kết quả, giờ, vị trí đang đứng → nhánh lưu) |
| **Cây** | Cây lớn kèm thanh **Breadcrumb** theo nhánh/API đang chọn, đồng bộ tiêu đề cửa sổ khi bấm **Mở cửa sổ riêng** |
| **Audit** | 4 tab con dạng thẻ (`tabType=card`) có số đếm: **Cần xử lý** kèm thẻ **Chi tiết quyết định** chứa nút xử lý trực tiếp, **Giữ chỗ**, **API trùng & ẩn**, và **Nhật ký** tô màu theo hành động (hoàn tác được) |

## Phím tắt

| Phím tắt | Chức năng |
|---|---|
| `Ctrl+K` | Mở **Spotlight** tìm kiếm toàn cục (nhảy nhanh tới API theo `#gid` / path / STT Excel, Nhánh, hoặc Role) |
| `Ctrl+F` | Focus ô lọc / tìm kiếm trong tab hiện tại |
| `Ctrl+Shift+C` | Copy `STT. path` cho các API / nhánh đang chọn |
| `Ctrl+Enter` | Gửi API / phạm vi đang chọn qua N Roles |
| `Ctrl+R` | Đẩy API / phạm vi đang chọn sang Burp Repeater |
| `Alt+1` .. `Alt+7` | Chuyển nhanh giữa 7 tab chính (Cây, API, Ma trận Role, Báo cáo, Roles, Cần xử lý, Cài đặt) |
| `Delete` | Xóa các API đang chọn (có hộp thoại xác nhận, vẫn giữ chỗ) |

## Workspace - dữ liệu không mất khi tắt Burp / đổi project

Mọi thứ (request / response, kết quả từng role, cây nhánh, giữ chỗ, file Excel, lượt gặp, bộ đếm, cấu hình cột bảng, cỡ chữ và animation) lưu vào thư mục **`theMythOfCrawl_workspace`** trong thư mục dự án chứa file `.jar`
(chạy từ `build/libs` thì là thư mục gốc của repo):

```
theMythOfCrawl_workspace\
└── <Tên project Burp>\
    └── <Tên workspace>\
        ├── workspace.json      tên, thời gian tạo
        ├── state.json          nhánh, giữ chỗ, Excel, lượt gặp, quy tắc role, bộ đếm
        └── apis\<số API>\      meta.json + request / response gốc và của từng role
```

- Tự lưu mỗi 2 giây, chỉ ghi phần thay đổi; ghi kiểu nguyên tử (tắt Burp đột ngột không hỏng file).
- Mở lại Burp: tự mở workspace dùng gần nhất của project đó. Menu **Workspace**: tạo / mở / xóa, mở thư mục, đổi thư mục gốc.
- `theMythOfCrawl_workspace/` nằm trong `.gitignore`: dữ liệu bắt được (có token, cookie) không bao giờ bị đẩy lên GitHub.

## Hiệu năng (đo bằng `Bench`, 5000 API × 10 role)

| Thao tác | Thời gian |
|---|---|
| Bắt 5000 API (nhân bản 10 role) | ~4.1 s (~0.83 ms / API) |
| Dựng lại cây 5000 API | ~55 ms (mục tiêu <= 80 ms) |
| Nạp lại bảng API 5000 dòng | ~121 ms (mục tiêu <= 150 ms) |
| Dựng báo cáo Lịch sử trực quan (`HistoryReportView`) | ~31–44 ms |
| Lưu workspace | ~0.2–0.5 s |
| Mở lại workspace 5000 API | ~1.7 s |
| Xuất Excel 7 sheet | ~1.2 s |
| Bộ nhớ giữ lại | ~193 MB |

Giao diện chỉ cập nhật phần thay đổi (gom mỗi 200 ms), cây và danh sách thẻ lịch sử chỉ dựng phần tử đang nhìn thấy (virtualization / lazy tree), tab ẩn không vẽ lại, renderer cache màu/font/chip không tạo object thừa khi vẽ.

## Dựng request cho từng role

`src/main/java/themythofcrawl/rolereq/RoleRules.java` dựng request của mỗi role (header / cookie / JWT)
và của profile API gốc từ request đã bắt - giống bản Python. Hành vi được giữ bằng `RoleRulesTest`
(chuyển từ các test của bản Python).

## Kiểm thử

```bat
gradlew.bat test
```

- `CoreTest`, `EngineTest`, `SendDispatcherTest`, `WorkspaceTest`: lõi, bắt / gửi / 429 / giữ chỗ / trùng, lưu & mở lại.
- `RoleRulesTest`: request của từng role (thay / thêm / xóa header, cookie, JWT, profile API gốc, chạy song song).
- `ThemeContrastTest`: kiểm tra độ tương phản WCAG AA (>= 4.5:1 cho token, >= 3:1 cho chữ phụ) trên nền sáng/tối, kiểm tra mọi kit component & icon Java2D, và ràng buộc zero FlatLaf import / zero `new Color(` ngoài `Theme` / zero emoji literal trong `ui/`.
- `ThemedComponentTest`: kiểm tra `ThemedTree`/`ThemedTable` bảo toàn renderer qua `applyTheme` & runtime switch `FlatLightLaf / FlatDarkLaf / FlatIntelliJLaf / FlatDarculaLaf`, bố cục header 1100 px, `HistoryReportView` với 5000 API, `SpotlightDialog`, kéo thả + Hoàn tác, `ColumnMemory`, cỡ chữ, `SearchField`.
- `UiSmokeTest`, `ExtensionLoadTest`: dựng giao diện headless, nạp extension đúng chuẩn Montoya và lưu ra đĩa.
- `UiPreview` / `Bench` (trong `src/test`): chụp ma trận ảnh màn hình trên 4 theme FlatLaf (`-Dlaf=...`, `testImplementation` only — không đóng gói vào `.jar`) và benchmark 5000 API.
