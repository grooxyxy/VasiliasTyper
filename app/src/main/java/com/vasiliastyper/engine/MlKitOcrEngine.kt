package com.vasiliastyper.engine
  import android.graphics.Bitmap;import android.graphics.RectF
  import com.google.mlkit.vision.common.InputImage;import com.google.mlkit.vision.text.TextRecognition;import com.google.mlkit.vision.text.TextRecognizer
  import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
  import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
  import com.google.mlkit.vision.text.latin.TextRecognizerOptions
  object MlKitOcrEngine {
      const val LANG_AUTO="auto";const val LANG_EN="en";const val LANG_ZH="zh";const val LANG_KO="ko"
      interface OcrCallback{fun onSuccess(text:String,lang:String);fun onFailure(e:Exception)}
      // v5.4: per-block callback — each element = one detected text region (speech bubble)
      interface OcrBlocksCallback{fun onSuccess(blocks:List<String>,lang:String);fun onFailure(e:Exception)}
      private val latinRec by lazy{TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)}
      private val chRec by lazy{TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())}
      private val koRec by lazy{TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())}
      fun recognize(bitmap:Bitmap,crop:RectF?,lang:String,cb:OcrCallback){
          val bmp = preprocess(crop?.let { cropBmp(bitmap, it) } ?: bitmap)
          when(lang){LANG_ZH->run(chRec,bmp,LANG_ZH,cb);LANG_KO->run(koRec,bmp,LANG_KO,cb);LANG_EN->run(latinRec,bmp,LANG_EN,cb);else->auto(bmp,cb)}
      }
      fun recognizeBlocks(bitmap:Bitmap,lang:String,cb:OcrBlocksCallback){
          val img = InputImage.fromBitmap(preprocess(bitmap),0)
          fun extractBlocks(result:com.google.mlkit.vision.text.Text,detLang:String){
              val raw=result.textBlocks.sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 })).map{it.text.trim()}.filter{it.isNotBlank()}
              val blocks=if(raw.isEmpty()&&result.text.isNotBlank()) listOf(result.text.trim()) else raw
              cb.onSuccess(blocks,detLang)
          }
          when(lang){
              LANG_ZH->chRec.process(img).addOnSuccessListener{extractBlocks(it,LANG_ZH)}.addOnFailureListener{cb.onFailure(it)}
              LANG_KO->koRec.process(img).addOnSuccessListener{extractBlocks(it,LANG_KO)}.addOnFailureListener{cb.onFailure(it)}
              LANG_EN->latinRec.process(img).addOnSuccessListener{extractBlocks(it,LANG_EN)}.addOnFailureListener{cb.onFailure(it)}
              else->autoBlocks(img,cb)
          }
      }
      private fun autoBlocks(img:InputImage,cb:OcrBlocksCallback){
          latinRec.process(img).addOnSuccessListener{lr->
              chRec.process(img).addOnSuccessListener{zr->
                  koRec.process(img).addOnSuccessListener{kr->
                      val lat=lr.text.trim();val zh=zr.text.trim();val ko=kr.text.trim()
                      var bestResult=lr;var l=LANG_EN
                      if(zh.any{c->c.code in 0x4E00..0x9FFF}&&zh.length>lat.length){bestResult=zr;l=LANG_ZH}
                      if(ko.any{c->c.code in 0xAC00..0xD7A3}&&ko.length>lat.length&&ko.length>zh.length){bestResult=kr;l=LANG_KO}
                      val raw=bestResult.textBlocks.sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 })).map{it.text.trim()}.filter{it.isNotBlank()}
                      val blocks=if(raw.isEmpty()&&bestResult.text.isNotBlank()) listOf(bestResult.text.trim()) else raw
                      cb.onSuccess(blocks,l)
                  }.addOnFailureListener{
                      val raw=lr.textBlocks.sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 })).map{it.text.trim()}.filter{it.isNotBlank()}
                      cb.onSuccess(if(raw.isEmpty())listOf(lr.text.trim()) else raw,LANG_EN)
                  }
              }.addOnFailureListener{
                  val raw=lr.textBlocks.sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 })).map{it.text.trim()}.filter{it.isNotBlank()}
                  cb.onSuccess(if(raw.isEmpty())listOf(lr.text.trim()) else raw,LANG_EN)
              }
          }.addOnFailureListener{cb.onFailure(it)}
      }
      private fun auto(bmp:Bitmap,cb:OcrCallback){
          val img = InputImage.fromBitmap(bmp,0)
          latinRec.process(img).addOnSuccessListener{lr->
              chRec.process(img).addOnSuccessListener{zr->
                  koRec.process(img).addOnSuccessListener{kr->
                      val lat=lr.text.trim();val zh=zr.text.trim();val ko=kr.text.trim()
                      var best=lat;var l=LANG_EN
                      if(zh.any{c->c.code in 0x4E00..0x9FFF}&&zh.length>best.length){best=zh;l=LANG_ZH}
                      if(ko.any{c->c.code in 0xAC00..0xD7A3}&&ko.length>best.length){best=ko;l=LANG_KO}
                      cb.onSuccess(best,l)
                  }.addOnFailureListener{val lat=lr.text.trim();val zh=zr.text.trim();cb.onSuccess(if(zh.length>lat.length)zh else lat,if(zh.length>lat.length)LANG_ZH else LANG_EN)}
              }.addOnFailureListener{cb.onSuccess(lr.text.trim(),LANG_EN)}
          }.addOnFailureListener{cb.onFailure(it)}
      }
      private fun run(r:TextRecognizer,bmp:Bitmap,lang:String,cb:OcrCallback){r.process(InputImage.fromBitmap(bmp,0)).addOnSuccessListener{cb.onSuccess(it.text.trim(),lang)}.addOnFailureListener{cb.onFailure(it)}}
      private fun preprocess(src: Bitmap): Bitmap {
          val base = when {
              src.width < 600 || src.height < 600 -> Bitmap.createScaledBitmap(
                  src,
                  (src.width * 3).coerceAtMost(3072),
                  (src.height * 3).coerceAtMost(3072),
                  true
              )
              src.width < 1200 || src.height < 1200 -> Bitmap.createScaledBitmap(
                  src,
                  (src.width * 2).coerceAtMost(3072),
                  (src.height * 2).coerceAtMost(3072),
                  true
              )
              else -> src
          }
          val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
          val canvas = android.graphics.Canvas(out)
          canvas.drawColor(android.graphics.Color.WHITE)
          val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
              colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(floatArrayOf(
                  1.28f, 0f,    0f,    0f, -12f,
                  0f,    1.28f, 0f,    0f, -12f,
                  0f,    0f,    1.28f, 0f, -12f,
                  0f,    0f,    0f,    1f,   0f
              )))
          }
          canvas.drawBitmap(base, 0f, 0f, paint)
          return out
      }
      private fun cropBmp(src:Bitmap,r:RectF):Bitmap{val x=r.left.toInt().coerceIn(0,src.width-1);val y=r.top.toInt().coerceIn(0,src.height-1);val w=r.width().toInt().coerceIn(1,src.width-x);val h=r.height().toInt().coerceIn(1,src.height-y);return Bitmap.createBitmap(src,x,y,w,h)}
  }
  