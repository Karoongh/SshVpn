# Karoon Tunnel (SSH VPN for Android)

یک برنامه VPN برای اندروید که ترافیک TCP را از طریق یک سرور SSH عبور می‌دهد.

## معماری کلی

```
برنامه‌ها → TUN (VpnService) → hev-socks5-tunnel (tun2socks)
→ SOCKS5 محلی (127.0.0.1) → کانال direct-tcpip روی SSH → اینترنت
```

- DNS با mapdns داخل tun2socks گرفته می‌شود و نام دامنه‌ها سمت سرور SSH resolve می‌شوند.
- ترافیک UDP به‌جز DNS (مثلاً QUIC و تماس‌های صوتی/تصویری) از SSH عبور نمی‌کند.
- سرور باید `AllowTcpForwarding yes` داشته باشد.
- Application ID فعلی: `com.example.sshvpn`.

## ساخت APK

1. پروژه را در Android Studio باز کنید.
2. از SDK Manager، ابزارهای NDK (Side by side) نسخه `26.1.10909125` و CMake را نصب کنید.
3. منتظر بمانید Gradle Sync کامل شود.
4. در ترمینال ریشه پروژه اجرا کنید:
   - Windows: `gradlew.bat :app:assembleDebug`
   - macOS/Linux: `./gradlew :app:assembleDebug`
5. خروجی Debug در `app/build/outputs/apk/debug/app-debug.apk` قرار می‌گیرد.

برای جزئیات Build، عیب‌یابی نصب و امضای APK، فایل [راهنمای Build و Signing](docs/BUILD_AND_SIGNING.md) را بخوانید. دستورالعمل‌های لازم برای ایجنت‌های کدنویسی در [agents.md](agents.md) آمده است.

### وابستگی native

اگر خطای سورس native دیدید، بررسی کنید پوشه `app/src/main/jni/hev-socks5-tunnel/third-part/` شامل پوشه‌های `lwip`، `yaml` و `hev-task-system` و محتوای کامل آن‌ها باشد.

## فرمت‌های کانفیگ قابل وارد کردن

| فرمت | مثال |
| --- | --- |
| لینک SSH | `ssh://user:pass@1.2.3.4:22#نام` |
| ساده | `1.2.3.4:22@user:pass` |
| چهار بخشی | `1.2.3.4:22:user:pass` |
| JSON | `{"host":"1.2.3.4","port":22,"username":"user","password":"pass"}` |
| key=value | خط‌های `host=` و `port=` و `user=` و `pass=` |
| base64 | هر کدام از موارد بالا به‌صورت base64 |

با دکمه «از کلیپ‌بورد» یا «از فایل» وارد کنید یا فیلدها را دستی پر کنید. احراز هویت با کلید خصوصی (PEM/OpenSSH) هم پشتیبانی می‌شود.

## محدودیت‌ها و ملاحظات امنیتی

- بررسی کلید میزبان SSH غیرفعال است (`StrictHostKeyChecking=no`)؛ در نتیجه امکان تشخیص سرور جعلی در حمله MITM وجود ندارد.
- رمز و کانفیگ در SharedPreferences ذخیره می‌شود و رمزگذاری جداگانه ندارد.
- IPv6 از تونل عبور نمی‌کند.
- اتصال خودکار مجدد پس از قطع SSH وجود ندارد.
- تا وقتی روی دستگاه واقعی آزمایش نشده، عملکرد کامل VPN تأییدشده محسوب نمی‌شود.

## وابستگی‌ها و مجوزها

- [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) — MIT
- [mwiede/jsch](https://github.com/mwiede/jsch) — BSD

## وضعیت بررسی خطای نصب (2026-10-12)

تصویر ارائه‌شده خطای `Package signature does not match the installed app` را نشان می‌دهد. این خطا به امضای APK و نسخه نصب‌شده مربوط است و به‌تنهایی خرابی سورس یا شکست کامپایل را اثبات نمی‌کند. پیش از حذف برنامه، امضای APK جدید و نصب‌شده را با راهنمای `docs/BUILD_AND_SIGNING.md` مقایسه کنید. حذف نصب ممکن است داده‌های محلی برنامه را پاک کند.

**وضعیت فعلی:** علت کلی خطا مشخص است؛ اما تا زمان مقایسه گواهی نسخه نصب‌شده با APK جدید و اجرای واقعی Gradle build، ریشه دقیق اختلاف امضا و سلامت Build تأیید نشده‌اند.
