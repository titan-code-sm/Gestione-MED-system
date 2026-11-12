// Attività principale dell'app Android di MED System. Il workflow «App Android
// (tablet)» copia questo file nel progetto Android generato da Tauri,
// sostituendo PACCHETTO con l'identificativo della versione (it.medsystem.gineco…).
//
// Aggiunge alla pagina l'oggetto JavaScript «MedSystemAndroid» (usato da
// desktop-bridge.js) con le due cose che la finestra di Android non sa fare:
//  - stampa(html, titolo): apre la finestra di stampa di Android (stampanti,
//    «Salva come PDF») con la pagina da stampare, come «Stampa» su Windows
//  - apri(percorso, tipo): apre un file salvato dall'app (PDF, immagini) con
//    l'app del tablet adatta, come il doppio clic su Windows
//  - scaricaAggiornamento(url) / installaAggiornamento(): «Aggiorna» del
//    portale. Scarica in sottofondo il nuovo .apk dalle release di MED System e
//    poi apre l'installazione di Android (il medico preme «Aggiorna»). Android
//    accetta l'aggiornamento solo se è firmato con la stessa chiave: i dati restano.
//  - apriWeb(indirizzo): apre un link web (sito, PDF online) con il browser.
package PACCHETTO

import android.content.Context
import android.content.Intent
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : TauriActivity() {
  override fun onWebViewCreate(webView: WebView) {
    super.onWebViewCreate(webView)
    // Android 15+ disegna l'app anche sotto la barra di stato e quella dei
    // pulsanti (indietro/home): la barra «Visita in corso» in fondo finiva
    // sotto i pulsanti di Android e non si poteva toccare. Il contenuto resta
    // tra le barre di sistema (e sopra la tastiera quando è aperta).
    val contenuto = findViewById<android.view.View>(android.R.id.content)
    androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(contenuto) { v, insets ->
      val barre = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
      val tastiera = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
      v.setPadding(barre.left, barre.top, barre.right, maxOf(barre.bottom, tastiera.bottom))
      androidx.core.view.WindowInsetsCompat.CONSUMED
    }
    androidx.core.view.ViewCompat.requestApplyInsets(contenuto)
    webView.addJavascriptInterface(PonteMedSystem(this, webView), "MedSystemAndroid")
  }
}

// FileProvider di MED System (authority <pacchetto>.file, cartelle in
// percorsi_file.xml). Serve una classe sua: Tauri registra già
// androidx.core.content.FileProvider con altre cartelle e, con due provider
// della stessa classe, Android usava quello di Tauri anche per i nostri link.
class FileMedSystem : FileProvider()

class PonteMedSystem(private val attivita: MainActivity, private val finestra: WebView) {
  private val origineRelease = "https://github.com/titan-code-sm/Gestione-MED-system/releases/download/"
  @Volatile private var apkScaricato: File? = null

  // La pagina di stampa deve restare in memoria finché Android la usa
  private var paginaInStampa: WebView? = null

  @JavascriptInterface
  fun stampa(html: String, titolo: String) {
    attivita.runOnUiThread {
      val pagina = WebView(attivita)
      pagina.settings.javaScriptEnabled = false
      pagina.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String?) {
          val nome = titolo.ifBlank { "MED System" }
          val gestore = attivita.getSystemService(Context.PRINT_SERVICE) as PrintManager
          gestore.print(nome, view.createPrintDocumentAdapter(nome),
            PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
        }
      }
      paginaInStampa = pagina
      pagina.loadDataWithBaseURL("https://tauri.localhost/", html, "text/html", "UTF-8", null)
    }
  }

  private fun rispondiAggiornamento(stato: String, messaggio: String) {
    val js = "window.__medsysAggiornamento && window.__medsysAggiornamento(" +
      JSONObject.quote(stato) + "," + JSONObject.quote(messaggio) + ")"
    attivita.runOnUiThread { finestra.evaluateJavascript(js, null) }
  }

  // Un solo scaricamento alla volta: all'accesso l'app controlla l'aggiornamento
  // due volte quasi insieme; prima della 1.8.8 i due scaricamenti scrivevano
  // nello stesso file e Android trovava un .apk rovinato («Errore durante
  // l'analisi del pacchetto»). Ora ogni scaricamento va in un file suo, si
  // controlla che sia completo e che Android lo sappia leggere, e solo allora
  // diventa il file da installare.
  private val inCorso = java.util.concurrent.atomic.AtomicBoolean(false)

  @JavascriptInterface
  fun scaricaAggiornamento(url: String): Boolean {
    if (!url.startsWith(origineRelease) || !url.endsWith(".apk")) return false
    if (!inCorso.compareAndSet(false, true)) return true   // risponde lo scaricamento già avviato
    Thread {
      val cartella = File(attivita.cacheDir, "aggiornamento")
      cartella.mkdirs()
      val parziale = File(cartella, "scarico-" + System.currentTimeMillis() + ".part")
      try {
        var indirizzo = URL(url)
        var connessione: HttpURLConnection
        var salti = 0
        while (true) {
          connessione = indirizzo.openConnection() as HttpURLConnection
          connessione.instanceFollowRedirects = false
          connessione.connectTimeout = 20000
          connessione.readTimeout = 60000
          val codice = connessione.responseCode
          if (codice in 300..399 && salti < 5) {
            indirizzo = URL(indirizzo, connessione.getHeaderField("Location"))
            connessione.disconnect()
            salti++
            continue
          }
          if (codice != 200) throw Exception("download non riuscito (codice $codice)")
          break
        }
        val attesi = connessione.contentLengthLong
        connessione.inputStream.use { ingresso -> FileOutputStream(parziale).use { ingresso.copyTo(it) } }
        connessione.disconnect()
        if (attesi > 0 && parziale.length() != attesi) throw Exception("download incompleto (" + parziale.length() + " di " + attesi + " byte): riprova")
        val info = attivita.packageManager.getPackageArchiveInfo(parziale.absolutePath, 0)
          ?: throw Exception("il file scaricato non è un aggiornamento valido: riprova")
        if (info.packageName != attivita.packageName) throw Exception("il file scaricato è di un'altra app (" + info.packageName + ")")
        val pronto = File(cartella, "medsystem-" + System.currentTimeMillis() + ".apk")
        if (!parziale.renameTo(pronto)) throw Exception("impossibile preparare l'aggiornamento")
        cartella.listFiles()?.forEach { if (it != pronto) it.delete() }
        apkScaricato = pronto
        inCorso.set(false)
        rispondiAggiornamento("ok", "")
      } catch (e: Exception) {
        parziale.delete()
        inCorso.set(false)
        rispondiAggiornamento("errore", e.message ?: "download non riuscito")
      }
    }.start()
    return true
  }

  @JavascriptInterface
  fun installaAggiornamento(): Boolean {
    val file = apkScaricato ?: return false
    if (!file.exists()) return false
    return try {
      val uri = FileProvider.getUriForFile(attivita, attivita.packageName + ".file", file)
      val intento = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
      attivita.startActivity(intento)
      true
    } catch (e: Exception) {
      false
    }
  }

  @JavascriptInterface
  fun apri(percorso: String, tipo: String): Boolean {
    return try {
      val file = File(percorso)
      if (!file.exists()) return false
      val uri = FileProvider.getUriForFile(attivita, attivita.packageName + ".file", file)
      val intento = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, tipo.ifBlank { "application/octet-stream" })
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
      attivita.startActivity(Intent.createChooser(intento, "Apri con").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      true
    } catch (e: Exception) {
      false
    }
  }

  // Link web (siti, PDF online) dal tablet: li apre Android con l'app adatta.
  @JavascriptInterface
  fun apriWeb(indirizzo: String): Boolean {
    if (!indirizzo.startsWith("https://") && !indirizzo.startsWith("http://") && !indirizzo.startsWith("mailto:") && !indirizzo.startsWith("tel:")) return false
    return try {
      val intento = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(indirizzo)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      attivita.startActivity(intento)
      true
    } catch (e: Exception) {
      false
    }
  }
}
