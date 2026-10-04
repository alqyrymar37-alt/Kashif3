# كاشف الأرقام الجديد

## البناء عبر GitHub (بدون Android Studio)
1. أنشئ مستودعاً **خاصاً (Private)** على GitHub وارفع محتويات هذا المجلد (settings.gradle.kts في الجذر).
2. Settings → Secrets and variables → Actions → أضف: SUPABASE_URL و SUPABASE_ANON_KEY.
3. Actions → Build → ستجد ملف APK في Artifacts: نزّله وثبّته على هاتفك للتجربة.

## نسخة النشر (Google Play)
1. Actions → Create upload keystore → Run. نزّل Artifact باسم keystore-secrets، وانسخ قيمه إلى Secrets بالأسماء نفسها: KEYSTORE_BASE64 و KEYSTORE_PASSWORD و KEY_ALIAS و KEY_PASSWORD. احذف الملف بعدها واحتفظ بنسخة احتياطية آمنة.
2. Actions → Build → Run workflow → فعّل release. ستحصل على app-release.aab (لـ Google Play) و APK موقّع (للتوزيع المباشر).

## الخادم
شغّل backend/schema.sql ثم backend/directory.sql في Supabase، وانشر الدالة functions/send-business-code (راجع التعليقات داخلها).

## قبل النشر
غيّر applicationId في app/build.gradle.kts إلى معرّف فريد لك. الكود لم يُجرَّب على جهاز: توقّع تصحيح أخطاء بسيطة عند أول بناء، والخطأ يظهر في سجل Actions.
