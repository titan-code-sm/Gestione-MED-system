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
package PACCHETTO

import android.content.Context
import android.content.Intent
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : TauriActivity() {
  override fun onWebViewCreate(webView: WebView) {
    super.onWebViewCreate(webView)
    webView.addJavascriptInterface(PonteMedSystem(this), "MedSystemAndroid")
  }
}

class PonteMedSystem(private val attivita: MainActivity) {
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
}
