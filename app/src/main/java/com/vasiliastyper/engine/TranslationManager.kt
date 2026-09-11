package com.vasiliastyper.engine
  import android.os.Handler;import android.os.Looper;import com.google.gson.JsonParser
  import okhttp3.*;import java.io.IOException
  object TranslationManager {
      interface Cb{fun onSuccess(r:String);fun onFailure(m:String)}
      private val client=OkHttpClient();private val main=Handler(Looper.getMainLooper())
      fun translate(text:String,src:String,tgt:String,cb:Cb){
          if(text.isBlank()||src==tgt){main.post{cb.onSuccess(text)};return}
          val url=HttpUrl.Builder().scheme("https").host("api.mymemory.translated.net")
              .addPathSegment("get").addQueryParameter("q",text).addQueryParameter("langpair","${ml(src)}|${ml(tgt)}").build()
          client.newCall(Request.Builder().url(url).build()).enqueue(object:okhttp3.Callback{
              override fun onFailure(call:Call,e:IOException){
                  main.post{cb.onFailure(e.message?:"Network error")}
              }
              override fun onResponse(call:Call,response:Response){
                  try{
                      val b=response.body?.string()
                      if(b==null){main.post{cb.onFailure("Empty response")};return}
                      val j=JsonParser.parseString(b).asJsonObject
                      if(j["responseStatus"].asString!="200"){
                          main.post{cb.onFailure(j["responseDetails"]?.asString?:"Translation failed")}
                          return
                      }
                      main.post{cb.onSuccess(j["responseData"].asJsonObject["translatedText"].asString)}
                  }catch(e:Exception){
                      main.post{cb.onFailure(e.message?:"Parse error")}
                  }
              }
          })
      }
      private fun ml(l:String)=when(l){"zh"->"zh-CN";"ko"->"ko";"id"->"id";else->"en"}
  }