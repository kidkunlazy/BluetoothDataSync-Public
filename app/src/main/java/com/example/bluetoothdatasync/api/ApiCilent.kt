package com.example.bluetoothdatasync.api

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class ApiClient {

    // 定义网络请求回调接口
    interface ApiCallback {
        fun onSuccess(response: Response)
        fun onFailure(e: IOException)
    }

    private val httpClient = OkHttpClient()

    companion object {
        private const val TAG = "ApiClient"
    }

    /**
     * 发送数据到服务器。
     * @param apiUrl 服务器地址
     * @param appId 应用ID
     * @param appKey 应用密钥
     * @param macAddress 设备MAC地址
     * @param rawDataHex 扫描到的原始数据（十六进制字符串）
     * @param callback 回调监听器
     */
    fun sendData(apiUrl: String, appId: String, appKey: String, macAddress: String, rawDataHex: String, callback: ApiCallback) {
        try {
            val jsonObject = JSONObject().apply {
                put("mac", macAddress)
                put("data", rawDataHex)
            }
            val requestBody = jsonObject.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(apiUrl)
                .addHeader("X-LC-Id", appId)
                .addHeader("X-LC-Key", appKey)
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "数据发送失败", e)
                    callback.onFailure(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    // response.use 确保响应体被关闭
                    response.use {
                        // 注意：response.body?.string() 只能被调用一次
                        val responseBodyString = it.body?.string()
                        Log.d(TAG, "服务器响应: $responseBodyString")
                        // 由于上面的 use {} 块会自动关闭响应，这里我们直接使用 it 即可
                        callback.onSuccess(it)
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "构建或发送请求时发生异常", e)
            callback.onFailure(IOException("构建请求时出错", e))
        }
    }
}
