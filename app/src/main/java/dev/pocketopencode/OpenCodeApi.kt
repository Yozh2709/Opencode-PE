package dev.pocketopencode

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OpenCodeApi(private val base: String, private val password: String) {
    val webOrigin: String get() = base
    val webUrl: String get() = base + "/?auth_token=" + java.net.URLEncoder.encode(android.util.Base64.encodeToString("opencode:$password".toByteArray(),android.util.Base64.NO_WRAP),"UTF-8")
    private val client = OkHttpClient.Builder().connectTimeout(3,TimeUnit.SECONDS).readTimeout(45,TimeUnit.SECONDS).callTimeout(60,TimeUnit.SECONDS).build()
    suspend fun call(path: String, method: String="GET", body: JSONObject?=null, directory: String?=null): String = withContext(Dispatchers.IO) {
        val url=(base+path).toHttpUrl().newBuilder().apply { if(directory!=null)addQueryParameter("directory",directory) }.build()
        val request=Request.Builder().url(url).header("Authorization",Credentials.basic("opencode",password))
            .method(method,if(method in listOf("POST","PUT","PATCH"))(body?:JSONObject()).toString().toRequestBody("application/json".toMediaType()) else null).build()
        client.newCall(request).execute().use { response ->
            val text=response.body?.string().orEmpty()
            check(response.isSuccessful) { "OpenCode HTTP ${response.code}: ${text.take(500)}" }; text
        }
    }
    suspend fun health()=JSONObject(call("/global/health")).optBoolean("healthy")
    suspend fun sessions(dir:String)=JSONArray(call("/session",directory=dir))
    suspend fun messages(dir:String,id:String)=JSONArray(call("/session/$id/message",directory=dir))
    suspend fun create(dir:String)=JSONObject(call("/session","POST",directory=dir)).getString("id")
    suspend fun prompt(dir:String,id:String,text:String,provider:String,model:String) {
        val body=JSONObject().put("parts",JSONArray().put(JSONObject().put("type","text").put("text",text)))
        if(provider.isNotBlank()&&model.isNotBlank())body.put("model",JSONObject().put("providerID",provider).put("modelID",model))
        call("/session/$id/prompt_async","POST",body,dir)
    }
    suspend fun abort(dir:String,id:String) { call("/session/$id/abort","POST",directory=dir) }
    suspend fun permissions(dir:String)=JSONArray(call("/permission",directory=dir))
    suspend fun permission(dir:String,id:String,allow:Boolean) { call("/permission/$id/reply","POST",JSONObject().put("reply",if(allow)"once" else "reject"),dir) }
    suspend fun permission(dir:String,id:String,reply:String) {
        require(reply in listOf("once","always","reject"))
        call("/permission/$id/reply","POST",JSONObject().put("reply",reply),dir)
    }
    suspend fun questions(dir:String)=JSONArray(call("/question",directory=dir))
    suspend fun answer(dir:String,id:String,answers:JSONArray) { call("/question/$id/reply","POST",JSONObject().put("answers",answers),dir) }
    suspend fun providers(dir:String)=JSONObject(call("/provider",directory=dir))
    suspend fun auth(dir:String,provider:String,key:String) { call("/auth/$provider","PUT",JSONObject().put("type","api").put("key",key),dir) }
    suspend fun diff(dir:String,id:String)=JSONArray(call("/session/$id/diff",directory=dir))
}
