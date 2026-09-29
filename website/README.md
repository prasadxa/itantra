# iTantra reviewer website

Static site (HTML, CSS, JavaScript) for SIH reviewers: what the prototype does, real screenshots
and videos, measured results, the APK download and a model installer.

- **Site:** Cloudflare Pages project `itantra` → https://itantra-106.pages.dev
- **Heavy files:** R2 bucket `itantra-assets` → https://pub-d139da76dbb3434bbcb332e210a95fcf.r2.dev
  - `apk/` debug APK · `media/` screenshots, thumbnails, diagrams, video, clips · `models/v1/` model pack

## Update

```bash
# 1. Rebuild staging (APK from app/build/outputs/apk/debug, media from docs/, models from models/)
website/scripts/stage-assets.sh
# 2. Upload to R2 (pass a subfolder to upload only that part, e.g. "apk" or "media")
website/scripts/upload-assets.sh apk
# 3. Deploy the pages
cd website && wrangler pages deploy
```

If the APK changes, update its size and SHA-256 in `public/index.html` (printed by step 1).

## Notes

- Files over 250 MB are split into 200 MiB parts because `wrangler r2 object put` is limited to
  300 MB per file. `install-models.sh` / `install-models.ps1` download the parts, join them,
  check SHA-256 against `models/v1/manifest.tsv`, and `adb push` the result to
  `/sdcard/Android/data/org.itantra.app/files/models`.
- The r2.dev URL is rate-limited by Cloudflare; connect a custom domain to the bucket for heavy use.
