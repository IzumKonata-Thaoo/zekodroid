# RBAC — Users / Sessions / Logs

## Vai trò và quyền

| Quyền | admin | operator | viewer |
|---|---|---|---|
| `agent.view` – xem thiết bị | ✅ | ✅ | ✅ |
| `agent.data` – SMS/danh bạ/vị trí/thông báo/keylog/passwords | ✅ | ✅ | ✅ |
| `agent.cmd` – gửi lệnh (shell, file, camera, mic…) | ✅ | ✅ | ❌ |
| `agent.stream` – xem màn hình/camera realtime | ✅ | ✅ | ❌ |
| `builder.use` – build APK | ✅ | ✅ | ❌ |
| `logs.view` | ✅ | ✅ | ✅ |
| `users.manage` | ✅ | ❌ | ❌ |
| `sessions.manage` | ✅ | ❌ | ❌ |

Mọi endpoint đều check quyền: thiếu quyền → HTTP 401 (`{error:"unauthorized"}` cho login,
`403` kèm tên quyền khi đã đăng nhập nhưng sai role).

## Lưu trữ

```sql
users(id, name UNIQUE, salt, hash, role, created, disabled)
sessions(token PK, user_id, name, role, ip, ua, created, last_seen, expires, revoked)
logs(id, ts, user, ip, action, detail)
```

- Mật khẩu: **PBKDF2-HMAC-SHA256, 100 000 vòng, salt 16 byte ngẫu nhiên**, so sánh constant-time.
- Session: token 24 byte ngẫu nhiên (48 hex), hết hạn **12 giờ**, có `revoked` để thu hồi tức thì,
  mỗi request cập nhật `last_seen`.
- Log ghi: `login.ok`, `login.fail`, `logout`, `bootstrap`, `user.create`, `user.delete`,
  `session.revoke`, `cmd` (kèm hwid), `builder.build`.

## Panel

Nút **⚙ Quản trị** (chỉ hiện với role có `users.manage` hoặc `logs.view`), 3 tab:

- **Users** — bảng user, form tạo (username/password/role), nút xoá (không xoá được admin).
- **Sessions** — ai đang đăng nhập, IP, lúc đăng nhập, hoạt động cuối, nút **thu hồi**.
- **Logs** — 300 dòng gần nhất: lúc / user / IP / hành động / chi tiết.

Header hiện `user · role`. Viewer không thấy nút Builder.

## Kiểm chứng

```
viewer1 perms: agent.view,agent.data,logs.view
  agents   : OK (xem được Pixel 7 Pro)
  logs     : OK
  cmd      : HTTP 401   ← chặn
  users    : HTTP 401   ← chặn
  builder  : HTTP 401   ← chặn

op1 (operator):
  builder  : OK  {ready:true}
  cmd      : HTTP 404 (agent "x" không tồn tại → quyền đã qua)
  users    : HTTP 401   ← chặn

panel: nút Quản trị hiện, tab Users 3 dòng, tab Logs 8 dòng
```

## Ghi chú bảo mật

- Tài khoản admin đầu tiên tạo từ `ZK_USER`/`ZK_PASS` lúc khởi động (chỉ khi bảng `users` rỗng).
- Đổi mật khẩu admin = xoá `users` row hoặc thêm user admin mới rồi xoá cái cũ.
- Cookie `zk_sess` HttpOnly + SameSite=Lax; chưa bật `Secure` vì panel chạy HTTP —
  đặt sau nginx/Caddy TLS thì thêm `Secure` (1 dòng trong `CookieOptions`).