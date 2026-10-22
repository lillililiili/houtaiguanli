# Application fonts

Both applications self-host these fonts. No Google Fonts CDN, OS font installation,
or `local()` source is used for the web application.

- **Noto Sans SC**: Chinese and proportional Latin text; static weight 400,
  with browser-synthesized heavier weights. Full character coverage from the
  existing PDF font is retained.
- **Noto Sans Mono**: Latin letters, numbers and code; variable weights 100–900.
  Chinese in code blocks falls back to the bundled Noto Sans SC.

Both are licensed under **SIL Open Font License 1.1**, allowing commercial use,
embedding and redistribution with the copyright and license notices. Keep
`NotoSansSC-OFL.txt` and `NotoSansMono-OFL.txt` alongside every distributed copy.
Do not sell the fonts on their own. Generated reports do not become OFL-licensed.

`manifest.json` records official upstream locations, source/output SHA-256 hashes
and conversion details. Web font startup waits for both local faces before the
first chart or map glyph is drawn. Font-loading failures are displayed explicitly.

Excel files reference `Noto Sans SC` but do not embed a font program. To preserve
their appearance in desktop Excel/WPS, install the official
[Noto Sans SC](https://github.com/notofonts/noto-cjk/tree/main/Sans)
on the machine that opens them. The PDF export embeds its font and needs no
recipient-side installation. Installing fonts on users' systems is not performed
by the web application.
