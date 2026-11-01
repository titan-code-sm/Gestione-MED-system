// Copia in app/consensi-pdf/ i PDF dei consensi caricati dal portale
// (Firestore pdfInAttesa/{file}, a pezzi in /parti). Lo usa il workflow
// «PDF dal portale»:
//   node scripts/pdf-dal-portale.cjs scarica   -> scrive i file, elenco in pdf-scaricati.txt
//   node scripts/pdf-dal-portale.cjs pulisci   -> dopo il push, li toglie da Firestore
// Credenziali: quelle dell'account di servizio (GOOGLE_APPLICATION_CREDENTIALS).
// firebase-admin viene da functions/node_modules (NODE_PATH).
const fs = require('node:fs');
const path = require('node:path');
const { initializeApp } = require('firebase-admin/app');
const { getFirestore } = require('firebase-admin/firestore');

const ROOT = path.resolve(__dirname, '..');
const CARTELLA = path.join(ROOT, 'app', 'consensi-pdf');
const ELENCO = path.join(ROOT, 'pdf-scaricati.txt');
initializeApp({ projectId: 'gynosistem' });
const db = getFirestore();

async function scarica() {
  const q = await db.collection('pdfInAttesa').get();
  const fatti = [];
  for (const doc of q.docs) {
    const file = doc.id;
    if (!/^[a-z0-9-]+\.pdf$/.test(file)) { console.warn('Nome non valido, salto:', file); continue; }
    const { parti, byte } = doc.data();
    const pezzi = (await doc.ref.collection('parti').orderBy('__name__').get()).docs.map((p) => Buffer.from(p.get('dati')));
    const buf = Buffer.concat(pezzi);
    if (pezzi.length !== parti || buf.length !== byte || buf.subarray(0, 5).toString('latin1') !== '%PDF-') {
      console.warn('File incompleto, riprovo al prossimo giro:', file); continue;
    }
    fs.mkdirSync(CARTELLA, { recursive: true });
    fs.writeFileSync(path.join(CARTELLA, file), buf);
    fatti.push(`${file}\t${doc.get('caricato') || ''}`);
    console.log(`  ${file} (${(buf.length / 1024).toFixed(0)} KB)`);
  }
  fs.writeFileSync(ELENCO, fatti.join('\n'));
  console.log(fatti.length ? `${fatti.length} PDF copiati.` : 'Nessun PDF in attesa.');
}

async function pulisci() {
  if (!fs.existsSync(ELENCO)) return;
  for (const riga of fs.readFileSync(ELENCO, 'utf8').split('\n').filter(Boolean)) {
    const [file, caricato] = riga.split('\t');
    const rif = db.collection('pdfInAttesa').doc(file);
    const doc = await rif.get();
    // Se nel frattempo è stato ricaricato, si tiene per il prossimo giro
    if (!doc.exists || (doc.get('caricato') || '') !== caricato) continue;
    const parti = await rif.collection('parti').get();
    const batch = db.batch();
    parti.docs.forEach((p) => batch.delete(p.ref));
    batch.delete(rif);
    await batch.commit();
  }
  fs.rmSync(ELENCO);
}

({ scarica, pulisci })[process.argv[2]]?.().catch((e) => { console.error(e); process.exit(1); })
  ?? (console.error('Uso: scarica | pulisci'), process.exit(1));
