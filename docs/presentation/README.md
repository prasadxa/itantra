# iTantra — SIH 2026 idea deck

`iTantra_SIH2026_v5.pptx` / `.pdf`: 6 slides on the official SIH 2026 idea template
(`SIH2026-IDEA-Presentation-Format.pptx`). Upload the **PDF** to the SIH portal.

## Rebuild
```bash
cd docs/presentation
python3 build_deck.py          # needs python-pptx, Pillow, lxml -> iTantra_SIH2026_v5.pptx (v4 kept for reference)
```
Export the PDF from PowerPoint (File → Export → PDF), or with LibreOffice.

- `icons/` lucide icons as PNG, rendered by `icons.js` (`npm install react-icons react react-dom sharp`, then `node icons.js`)
- `img/` real app screenshots (status bar cropped), PC control-room dashboard, logo, QR codes (website, demo film, GitHub, APK)
- `photos/` Wikimedia Commons photos; licences and authors in `photos/credits.json` (credited on slide 5)

## Content rules used
- Template frame and the idea-detail pointer headings are kept word for word.
- Numbers are the prototype's own measurements on a Snapdragon 870 phone with 12 GB RAM (LITE profile forced);
  the deck says so. Validation on 4–6 GB phones is in progress — update slide 4 when those numbers exist.
- Status is shown as Built / Test pending / Planned.
