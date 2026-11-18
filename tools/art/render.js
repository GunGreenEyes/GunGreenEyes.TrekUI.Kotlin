// Usage: node render.js jobs.json   (jobs: [{svg, out, width}])
const { Resvg } = require('@resvg/resvg-js');
const fs = require('fs');
const path = require('path');
const fontDir = path.join(__dirname, '..', 'fonts');
const jobs = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
for (const j of jobs) {
  const r = new Resvg(j.svg, { fitTo: { mode: 'width', value: j.width },
    font: { fontDirs: [fontDir], loadSystemFonts: false } });
  fs.writeFileSync(j.out, r.render().asPng());
}
console.log('rendered', jobs.length);
