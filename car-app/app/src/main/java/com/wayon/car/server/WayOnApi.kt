package com.wayon.car.server

import com.google.gson.JsonParser
import com.wayon.car.Config
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

/** 2-1 */
data class VehicleCreated(val carToken: String)

/** 2-2 */
data class ConnectCodeRequest(val kind: String = "QR")

/** 2-2. url은 QR에 그대로 넣는 폰 웹 주소 (`https://{웹}/join#코드`), expiresAt은 ISO-8601 UTC */
data class ConnectCode(val code: String, val url: String, val expiresAt: String)

/** 2-3 */
data class Passengers(val passengers: List<Passenger> = emptyList())

/**
 * 차량 앱 REST (app-api.md 2장). 지금은 등록, QR 발급, 동승자 목록·해제만.
 * 지난 대화 등은 그 화면을 붙일 때 더한다. 휴게소 지도는 아직 [RestAreaApi].
 */
interface WayOnApi {

    @POST("api/vehicles")
    suspend fun registerVehicle(): VehicleCreated

    @POST("api/vehicles/me/connect-codes")
    suspend fun issueConnectCode(
        @Header("Authorization") authorization: String,
        @Body body: ConnectCodeRequest = ConnectCodeRequest(),
    ): ConnectCode

    /** passengers.update는 바뀔 때만 오므로, 접속(auth.ok)할 때 한 번 받는다 */
    @GET("api/vehicles/me/passengers")
    suspend fun passengers(@Header("Authorization") authorization: String): Passengers

    /** 2-4. 해제된 폰은 서버가 4403으로 끊는다 */
    @DELETE("api/vehicles/me/passengers/{passengerId}")
    suspend fun deletePassenger(
        @Header("Authorization") authorization: String,
        @Path("passengerId") passengerId: Long,
    )

    companion object {
        fun create(baseUrl: String = Config.SERVER_URL): WayOnApi {
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            return Retrofit.Builder()
                .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(WayOnApi::class.java)
        }

        fun bearer(token: String) = "Bearer $token"
    }
}

/** 서버 오류 {"code", "message"} (app-api.md 8장). message는 차 화면에 그대로 보여도 되는 문장 */
data class ApiError(val code: String, val message: String)

/** 실패를 ApiError로. 응답이 없으면(서버 꺼짐·시간 초과) code는 NETWORK */
fun Throwable.toApiError(): ApiError {
    val body = (this as? HttpException)?.response()?.errorBody()?.string()
    val json = body?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
    return ApiError(
        code = json?.get("code")?.asString ?: if (this is HttpException) "HTTP_${code()}" else "NETWORK",
        message = json?.get("message")?.asString ?: "잠시 뒤에 다시 시도해 주세요",
    )
}
