# ساخت APK فقط با گوشی

## مسیر پیشنهادی: GitHub Actions
این پروژه فایل Workflow دارد و GitHub می‌تواند APK را روی سرور خودش بسازد؛ بنابراین Android Studio و کامپیوتر لازم نیست.

### 1) یک Repository خالی در GitHub بسازید
- در مرورگر گوشی وارد GitHub شوید.
- New repository را بزنید.
- Repository را Public یا Private انتخاب کنید.
- Repository را خالی بسازید (README را فعلاً اضافه نکنید).

### 2) فایل‌های پروژه را وارد Repository کنید
محتویات این ZIP را Extract کنید و تمام فایل‌ها و پوشه‌ها را در ریشه Repository آپلود کنید. باید پوشه‌های `app` و `.github` و فایل‌های `build.gradle.kts` و `settings.gradle.kts` در ریشه باشند.

> اگر مرورگر GitHub آپلود پوشه‌ها را اجازه نداد، از GitHub Codespaces یا Termux استفاده کنید؛ راهنمای کوتاه Termux در پایین آمده است.

### 3) اجرای Build
بعد از Push شدن فایل‌ها:
- تب Actions را باز کنید.
- workflow با نام `Build CryptoPulse APK` را انتخاب کنید.
- در صورت نیاز `Run workflow` را بزنید.
- صبر کنید تا Job سبز شود.

### 4) دریافت APK
داخل اجرای موفق Workflow، بخش **Artifacts** را باز کنید و `CryptoPulse-debug-apk` را دانلود کنید. فایل ZIP دانلودشده را باز کنید و APK را روی گوشی نصب کنید.

## Termux (اگر GitHub آپلود فایل‌ها سخت بود)
پس از نصب Termux و Git، پروژه Extract شده را در گوشی قرار دهید و از داخل پوشه پروژه اجرا کنید:

```bash
git init
git branch -M main
git add .
git commit -m "CryptoPulse v1.3"
git remote add origin https://github.com/YOUR_USER/YOUR_REPO.git
git push -u origin main
```

برای `git push` باید احراز هویت GitHub را طبق روش فعلی GitHub انجام دهید.
