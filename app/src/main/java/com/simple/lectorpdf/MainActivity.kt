package com.simple.lectorpdf

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.LruCache
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ListView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var list: ListView
    private lateinit var openButton: Button

    // El PdfRenderer solo se usa desde este hilo (no es thread-safe)
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var pageCount = 0
    private var currentUri: Uri? = null

    @Volatile private var generation = 0          // invalida renders pendientes
    private val pending = HashSet<Int>()
    private val cache = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 6).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount / 1024
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        list = ListView(this).apply {
            setBackgroundColor(Color.parseColor("#E0E0E0"))
            divider = ColorDrawable(Color.parseColor("#E0E0E0"))
            dividerHeight = (8 * resources.displayMetrics.density).toInt()
            visibility = View.GONE
        }
        openButton = Button(this).apply {
            text = getString(R.string.abrir)
            setOnClickListener { pickPdf() }
        }
        val root = FrameLayout(this).apply {
            addView(list, FrameLayout.LayoutParams(-1, -1))
            addView(openButton, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            // Evita que la barra de navegación tape la última página (Android 15 edge-to-edge)
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(0, 0, 0, insets.systemWindowInsetBottom)
                insets
            }
        }
        setContentView(root)

        intent?.data?.let { openPdf(it) }
    }

    // ---------- Abrir ----------

    private fun pickPdf() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_OPEN)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OPEN && resultCode == RESULT_OK) data?.data?.let { openPdf(it) }
    }

    private fun openPdf(uri: Uri) {
        closePdf()
        try {
            val r = try {
                rendererFrom(contentResolver.openFileDescriptor(uri, "r")!!)
            } catch (e: Exception) {
                // Algunos proveedores no entregan un archivo "seekable": copiar a caché
                rendererFrom(copyToCache(uri))
            }
            renderer = r
            pageCount = r.pageCount
            currentUri = uri
            title = fileName(uri)
            openButton.visibility = View.GONE
            list.visibility = View.VISIBLE
            list.adapter = PageAdapter()
            invalidateOptionsMenu()
        } catch (e: Exception) {
            closePdf()
            openButton.visibility = View.VISIBLE
            list.visibility = View.GONE
            Toast.makeText(this, R.string.error_abrir, Toast.LENGTH_LONG).show()
        }
    }

    private fun rendererFrom(fd: ParcelFileDescriptor): PdfRenderer =
        try {
            PdfRenderer(fd).also { pfd = fd }
        } catch (e: Exception) {
            fd.close(); throw e
        }

    private fun copyToCache(uri: Uri): ParcelFileDescriptor {
        val f = File(cacheDir, "temp.pdf")
        contentResolver.openInputStream(uri)!!.use { input ->
            f.outputStream().use { input.copyTo(it) }
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun fileName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        } catch (_: Exception) { }
        return uri.lastPathSegment ?: getString(R.string.app_name)
    }

    private fun invalidateRenders() {
        generation++
        pending.clear()
        cache.evictAll()
    }

    private fun closePdf() {
        invalidateRenders()
        val r = renderer
        val f = pfd
        renderer = null; pfd = null; pageCount = 0; currentUri = null
        executor.execute {
            try { r?.close() } catch (_: Exception) { }
            try { f?.close() } catch (_: Exception) { }
        }
    }

    // ---------- Compartir ----------

    private fun share() {
        val uri = currentUri ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(send, getString(R.string.compartir)))
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Error", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Menú ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_SHARE, 0, R.string.compartir)
            .setIcon(android.R.drawable.ic_menu_share)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, MENU_OPEN, 1, R.string.abrir)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(MENU_SHARE)?.isVisible = currentUri != null
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_SHARE -> { share(); true }
        MENU_OPEN -> { pickPdf(); true }
        else -> super.onOptionsItemSelected(item)
    }

    // ---------- Render de páginas ----------

    private fun requestPage(index: Int, width: Int, view: ImageView) {
        val r = renderer ?: return
        if (!pending.add(index)) return
        val gen = generation
        executor.execute {
            if (gen != generation) return@execute
            var bmp: Bitmap? = null
            try {
                val page = r.openPage(index)
                try {
                    val h = (width.toFloat() * page.height / page.width).toInt()
                    bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(Color.WHITE)
                        page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                } finally {
                    page.close()
                }
            } catch (_: Exception) { }
            main.post {
                if (gen != generation) return@post
                pending.remove(index)
                val b = bmp ?: return@post
                cache.put(index, b)
                if (view.tag == index) {
                    view.minimumHeight = 0
                    view.setImageBitmap(b)
                }
            }
        }
    }

    private inner class PageAdapter : BaseAdapter() {
        override fun getCount() = pageCount
        override fun getItem(position: Int) = position
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val iv = (convertView as? ImageView) ?: ImageView(this@MainActivity).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.WHITE)
                layoutParams = AbsListView.LayoutParams(-1, -2)
            }
            iv.tag = position
            val w = parent.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val cached = cache.get(position)
            if (cached != null && cached.width == w) {
                iv.minimumHeight = 0
                iv.setImageBitmap(cached)
            } else {
                iv.setImageBitmap(null)
                iv.minimumHeight = (w * 1.414f).toInt()   // placeholder tamaño A4
                requestPage(position, w, iv)
            }
            return iv
        }
    }

    // Rotación: re-renderizar al nuevo ancho
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (renderer != null) {
            invalidateRenders()
            list.post { (list.adapter as? BaseAdapter)?.notifyDataSetChanged() }
        }
    }

    override fun onDestroy() {
        closePdf()
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val REQ_OPEN = 1
        private const val MENU_SHARE = 1
        private const val MENU_OPEN = 2
    }
}
