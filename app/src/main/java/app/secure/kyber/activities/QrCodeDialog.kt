package app.secure.kyber.activities


import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import app.secure.kyber.R

object QrCodeDialog {

    fun showQrDialog(context: Context,shortId:String, name:String) {

        //Set up the dialog view
        val qrCodeDialog =
            LayoutInflater.from(context).inflate(R.layout.qr_code_dialog, null)
        val dialog = AlertDialog.Builder(context)
            .setView(qrCodeDialog)
            .create()

        val window = dialog.window
        window?.setLayout(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        window?.setBackgroundDrawableResource(android.R.color.transparent)


        //Finding views by their id
//        val closeButton = qrCodeDialog.findViewById<TextView>(R.id.close_btn)
        val qrCodeImageView = qrCodeDialog.findViewById<ImageView>(R.id.qrCodeImageView)


        val qrCodeBitmap =
            QrCodeGenerator.generateQrCode(shortId, name, 500, 500) //Generate the QR Code
        qrCodeImageView.setImageBitmap(qrCodeBitmap) // Assigning the image view the QR Code

        //Setup click listener for the close button
//        closeButton.setOnClickListener {
//            dialog.dismiss() //Close the dialog
//        }



        dialog.show() //Show the dialog

    }


    //Function to start the share intent
    fun shareText(text: String, context: Context) {
        val intent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, text)
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(intent, null)
        context.startActivity(shareIntent)
    }

}