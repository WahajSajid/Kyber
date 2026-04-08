package app.secure.kyber.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import app.secure.kyber.R
import android.widget.EditText
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.viewpager2.widget.ViewPager2
import app.secure.kyber.adapters.MediaPreviewPagerAdapter
import app.secure.kyber.dataClasses.SelectedMediaParcelable

class MediaPreviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ITEMS = "extra_items"
        const val EXTRA_RESULT_ITEMS = "extra_result_items"

    }

    private lateinit var viewPager: ViewPager2
    private lateinit var btnClose: ImageView
    private lateinit var btnSend: ImageView
    private lateinit var etCaption: EditText
    private lateinit var ivCameraIcon: ImageView

    private lateinit var items: ArrayList<SelectedMediaParcelable>
    private lateinit var pagerAdapter: MediaPreviewPagerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media_preview)

        viewPager = findViewById(R.id.viewPager)
        btnClose = findViewById(R.id.btnClose)
        btnSend = findViewById(R.id.btnSend)
        etCaption = findViewById(R.id.etCaption)
        ivCameraIcon = findViewById(R.id.ivCameraIcon)

        items = intent.getParcelableArrayListExtra(EXTRA_ITEMS) ?: arrayListOf()

        pagerAdapter = MediaPreviewPagerAdapter(items)
        viewPager.adapter = pagerAdapter

        // initialize caption with current item caption
        if (items.isNotEmpty()) etCaption.setText(items[0].caption ?: "")

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                // set caption field to selected item caption
                etCaption.setText(items[position].caption ?: "")
            }
        })

        // update caption into model on text change (simple text watcher)
        etCaption.addTextChangedListener {
            val pos = viewPager.currentItem
            if (pos in items.indices) items[pos].caption = it.toString()
        }

        btnClose.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        btnSend.setOnClickListener {
            // return updated list to caller
            val out = Intent().apply {
                putParcelableArrayListExtra(EXTRA_RESULT_ITEMS, items)
            }
            setResult(Activity.RESULT_OK, out)
            finish()
        }

        // optional: camera icon on left of caption (keeps screenshot look)
        ivCameraIcon.setOnClickListener {
            // you could open camera or allow replacing media — left as no-op for now
        }
    }
}