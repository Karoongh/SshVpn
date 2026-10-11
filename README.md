# SSH VPN (اندروید)

یک برنامه VPN ساده برای اندروید که کل ترافیک گوشی را از طریق یک سرور SSH عبور می‌دهد (مشابه حالت SSH-direct در NPV Tunnel).

## نحوه کار

```
برنامه‌ها -> TUN (VpnService) -> hev-socks5-tunnel (tun2socks) -> SOCKS5 محلی (127.0.0.1) -> کانال direct-tcpip روی SSH -> اینترنت
```

- ترافیک TCP از SSH عبور می‌کند. DNS با mapdns داخل tun2socks گرفته می‌شود و نام دامنه‌ها سمت سرور SSH resolve می‌شوند.
- ترافیک UDP غیر از DNS (مثلاً QUIC، تماس‌های صوتی/تصویری) از SSH عبور نمی‌کند و رها می‌شود. این محدودیت خود SSH است.
- سرور باید `AllowTcpForwarding yes` داشته باشد (پیش‌فرض OpenSSH).

## ساخت APK

1. Android Studio (نسخه Koala یا جدیدتر) را نصب کنید.
2. پوشه این پروژه را با Open باز کنید.
3. از SDK Manager، بخش SDK Tools، گزینه‌های **NDK (Side by side)** نسخه 26.1.10909125 و **CMake** را نصب کنید. اگر نسخه NDK دیگری نصب دارید، مقدار `ndkVersion` را در `app/build.gradle.kts` تغییر دهید.
4. منتظر Gradle Sync بمانید.
5. از منوی Build گزینه **Build APK(s)** را بزنید. فایل خروجی در `app/build/outputs/apk/` است.
6. APK را روی گوشی نصب کنید (نصب از منابع ناشناس باید مجاز باشد).

اگر خطای مربوط به سورس‌های native دیدید، مطمئن شوید پوشه `app/src/main/jni/hev-socks5-tunnel/third-part/` شامل سه پوشه `lwip`، `yaml` و `hev-task-system` و پر است.

## فرمت‌های کانفیگ قابل وارد کردن

| فرمت | مثال |
| --- | --- |
| لینک ssh | `ssh://user:pass@1.2.3.4:22#نام` |
| ساده | `1.2.3.4:22@user:pass` |
| چهار بخشی | `1.2.3.4:22:user:pass` |
| JSON | `{"host":"1.2.3.4","port":22,"username":"user","password":"pass"}` |
| key=value | خط‌های `host=` و `port=` و `user=` و `pass=` |
| base64 | هر کدام از موارد بالا به‌صورت base64 |

با دکمه «از کلیپ‌بورد» یا «از فایل» وارد کنید یا فیلدها را دستی پر کنید. احراز هویت با کلید خصوصی (PEM/OpenSSH) هم پشتیبانی می‌شود.

اگر فرمت کانفیگ شما چیز دیگری است، یک نمونه (با اطلاعات واقعی را حذف کنید) بدهید تا پارسر `SshConfig.kt` را با آن هماهنگ کنیم.

## نکات امنیتی و محدودیت‌ها

- **Host key بررسی نمی‌شود** (`StrictHostKeyChecking=no`)، مثل بیشتر برنامه‌های تونل. در شبکه‌ای که مورد حمله MITM است، سرور جعلی قابل تشخیص نیست. برای سخت‌تر کردن، pin کردن fingerprint سرور را به `SshVpnService.connectSsh` اضافه کنید.
- رمز و کانفیگ در SharedPreferences داخل حافظه خصوصی برنامه ذخیره می‌شود و رمزگذاری جداگانه ندارد.
- IPv6 تونل نمی‌شود.
- اتصال خودکار مجدد ندارد؛ با قطع SSH، VPN قطع می‌شود و باید دوباره وصل شوید.
- این کد روی دستگاه واقعی تست نشده و باید در Android Studio کامپایل و آزمایش شود.

## مجوزها

- کتابخانه tun2socks: [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) (MIT)
- کلاینت SSH: [mwiede/jsch](https://github.com/mwiede/jsch) (BSD)
