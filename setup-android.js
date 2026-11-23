// Wird vom GitHub-Workflow nach "npx cap add android" ausgefuehrt.
// Kopiert die Java-Dateien ins Android-Projekt, traegt Berechtigungen ein,
// setzt Signierschluessel, Version, WorkManager und Fuchs-Icon.
const fs = require('fs');
const path = require('path');

function findFile(dir, name) {
  if (!fs.existsSync(dir)) return null;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) { const r = findFile(p, name); if (r) return r; }
    else if (e.name === name) return p;
  }
  return null;
}
function fail(m) { console.error('FEHLER: ' + m); process.exit(1); }

const javaRoot = path.join('android', 'app', 'src', 'main', 'java');
const generated = findFile(javaRoot, 'MainActivity.java');
if (!generated) fail('MainActivity.java im Android-Projekt nicht gefunden');
const dir = path.dirname(generated);
const pkg = (fs.readFileSync(generated, 'utf8').match(/^\s*package\s+([\w.]+)\s*;/m) || [])[1];
if (!pkg) fail('Paketname nicht lesbar');
console.log('Android-Paket: ' + pkg);

for (const f of ['MainActivity.java', 'TankPlugin.java', 'AlarmWorker.java', 'UpdatePlugin.java']) {
  const src = fs.readFileSync(f, 'utf8').replace(/^\s*package\s+[\w.]+\s*;/m, 'package ' + pkg + ';');
  fs.writeFileSync(path.join(dir, f), src);
  console.log('kopiert: ' + f);
}

// ---- Manifest ----
const manifest = path.join('android', 'app', 'src', 'main', 'AndroidManifest.xml');
let xml = fs.readFileSync(manifest, 'utf8');
const perms = [
  '<uses-permission android:name="android.permission.INTERNET" />',
  '<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />',
  '<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />',
  '<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />',
  '<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />'
];
const missing = perms.filter(p => !xml.includes('"' + p.match(/android:name="([^"]+)"/)[1] + '"'));
if (missing.length) xml = xml.replace('</manifest>', missing.map(p => '    ' + p + '\n').join('') + '</manifest>');
if (!xml.includes('<queries>')) {
  xml = xml.replace('</manifest>',
    '    <queries>\n' +
    '        <intent><action android:name="android.intent.action.VIEW" /><data android:scheme="https" /></intent>\n' +
    '        <intent><action android:name="android.intent.action.VIEW" /><data android:scheme="geo" /></intent>\n' +
    '    </queries>\n</manifest>');
}
xml = xml.replace(/(<activity[^>]*?android:name="\.MainActivity"[^>]*?)android:launchMode="[^"]*"/, '$1android:launchMode="singleTask"');
if (!/android:launchMode="singleTask"/.test(xml)) xml = xml.replace(/(<activity[^>]*?android:name="\.MainActivity")/, '$1\n            android:launchMode="singleTask"');
if (!xml.includes('foxupdate')) {
  xml = xml.replace('</application>',
    '    <provider android:name="androidx.core.content.FileProvider" android:authorities="${applicationId}.foxupdate" android:exported="false" android:grantUriPermissions="true">\n' +
    '        <meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/fox_paths" />\n' +
    '    </provider>\n    </application>');
  console.log('Dateifreigabe eingetragen');
}
fs.writeFileSync(manifest, xml);

const res = path.join('android', 'app', 'src', 'main', 'res');
fs.mkdirSync(path.join(res, 'xml'), { recursive: true });
fs.writeFileSync(path.join(res, 'xml', 'fox_paths.xml'),
  '<?xml version="1.0" encoding="utf-8"?>\n<paths>\n    <cache-path name="update" path="." />\n</paths>\n');

// ---- Fester Signierschluessel (sonst lehnt Android jedes Update ab) ----
const gradle = path.join('android', 'app', 'build.gradle');
let g = fs.readFileSync(gradle, 'utf8');
fs.writeFileSync(path.join('android', 'app', 'fox-debug.keystore'),
  Buffer.from(fs.readFileSync('fox-debug.keystore.b64', 'utf8').replace(/\s+/g, ''), 'base64'));
if (!g.includes('fox-debug.keystore')) {
  const block =
    'android {\n' +
    '    signingConfigs {\n' +
    '        debug {\n' +
    "            storeFile file('fox-debug.keystore')\n" +
    "            storePassword 'android'\n" +
    "            keyAlias 'androiddebugkey'\n" +
    "            keyPassword 'android'\n" +
    '        }\n' +
    '    }\n';
  const g2 = g.replace(/^android\s*\{\s*\n/m, block);
  if (g2 === g) fail('android { } in build.gradle nicht gefunden');
  g = g2;
  console.log('Signierschluessel fest eingetragen');
}
const bn = process.env.BUILD_NUMBER;
if (bn) {
  const g2 = g.replace(/versionCode\s+\d+/, 'versionCode ' + bn).replace(/versionName\s+"[^"]*"/, 'versionName "1.' + bn + '"');
  if (g2 === g) fail('versionCode in build.gradle nicht gefunden');
  g = g2;
  console.log('Version: ' + bn);
}
if (!g.includes('work-runtime')) {
  const g2 = g.replace(/^dependencies\s*\{\s*\n/m,
    'dependencies {\n' +
    "    implementation 'androidx.work:work-runtime:2.9.1'\n");
  if (g2 === g) fail('dependencies { } in build.gradle nicht gefunden');
  g = g2;
  console.log('WorkManager eingetragen');
}
fs.writeFileSync(gradle, g);

// ---- Fuchs-Icon ----
if (!fs.existsSync('icons')) fail('Ordner "icons" fehlt (make-icons.py)');
for (const d of fs.readdirSync('icons', { withFileTypes: true })) {
  if (!d.isDirectory()) continue;
  const to = path.join(res, d.name);
  fs.mkdirSync(to, { recursive: true });
  for (const f of fs.readdirSync(path.join('icons', d.name))) fs.copyFileSync(path.join('icons', d.name, f), path.join(to, f));
}
fs.mkdirSync(path.join(res, 'values'), { recursive: true });
fs.writeFileSync(path.join(res, 'values', 'fox_colors.xml'),
  '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="fox_icon_bg">#111114</color>\n</resources>\n');
const adaptive =
  '<?xml version="1.0" encoding="utf-8"?>\n' +
  '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n' +
  '    <background android:drawable="@color/fox_icon_bg"/>\n' +
  '    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>\n' +
  '</adaptive-icon>\n';
fs.mkdirSync(path.join(res, 'mipmap-anydpi-v26'), { recursive: true });
fs.writeFileSync(path.join(res, 'mipmap-anydpi-v26', 'ic_launcher.xml'), adaptive);
fs.writeFileSync(path.join(res, 'mipmap-anydpi-v26', 'ic_launcher_round.xml'), adaptive);
console.log('OK: Fox Tank eingerichtet');
