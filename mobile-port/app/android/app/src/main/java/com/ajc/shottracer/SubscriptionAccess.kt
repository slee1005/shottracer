package com.ajc.shottracer

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.*
import com.android.billingclient.api.*
import org.json.JSONObject
import java.security.*
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID

/** Store calls only; source videos and tracking data never leave this device. */
internal class SubscriptionAccess(context: Context, private val changed: () -> Unit) : AutoCloseable {
    private val app=context.applicationContext
    private val prefs=app.getSharedPreferences("subscription-v1",Context.MODE_PRIVATE)
    private val handler=Handler(Looper.getMainLooper())
    private var closed=false
    private var storageOK=true
    private val started=prefs.getLong("started",System.currentTimeMillis())
    private var trialUntil=prefs.getLong("trialUntil",started+THREE_DAYS_MS)
    private var seen=prefs.getLong("seen",started)
    private val bootWall=maxOf(System.currentTimeMillis(),seen)
    private val bootTick=SystemClock.elapsedRealtime()
    private var lease=prefs.getLong("storeUntil",0)
    private var used=prefs.getStringSet("used",emptySet()).orEmpty().toSet()
    val device=prefs.getString("device",null) ?: UUID.randomUUID().toString()
    private var product: ProductDetails?=null
    private var pending=false
    private var dialog: AlertDialog?=null
    private var redraw: (() -> Unit)?=null
    private val client=BillingClient.newBuilder(app).setListener { result, purchases ->
        if(result.responseCode==BillingClient.BillingResponseCode.OK) handlePurchases(purchases.orEmpty())
        else if(result.responseCode!=BillingClient.BillingResponseCode.USER_CANCELED) update()
    }.enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection().build()
    private val tick=object: Runnable { override fun run() { if(closed)return; persist(); update(); handler.postDelayed(this,30_000) } }
    init { persist(); handler.post(tick) }
    private fun now()=maxOf(System.currentTimeMillis(),bootWall+SystemClock.elapsedRealtime()-bootTick)
    private fun clockValid()=System.currentTimeMillis()+300_000>=seen
    fun needsIntroduction()=!prefs.getBoolean("introduced",false)
    fun allowed()=accessAllowed(now(),if(storageOK) trialUntil else 0,lease,clockValid())
    private fun persist(): Boolean {
        seen=maxOf(seen,now())
        storageOK=prefs.edit().putLong("started",started).putLong("trialUntil",trialUntil).putLong("seen",seen).putLong("storeUntil",lease).putString("device",device).putStringSet("used",used).commit()
        return storageOK
    }
    private fun update() { handler.post { if(!closed){ redraw?.invoke(); changed() } } }
    fun refresh() {
        if(client.isReady) query() else client.startConnection(object: BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) { if(result.responseCode==0) query() else update() }
            override fun onBillingServiceDisconnected() { update() }
        })
    }
    private fun query() {
        client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(listOf(
            QueryProductDetailsParams.Product.newBuilder().setProductId(PRODUCT).setProductType(BillingClient.ProductType.SUBS).build())).build()) { result, details ->
            if(result.responseCode==0) product=details.productDetailsList.firstOrNull { it.productId==PRODUCT }
            update()
        }
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()) { result,purchases ->
            if(result.responseCode==0) handlePurchases(purchases,true) else update()
        }
    }
    private fun verified(p: Purchase): Boolean = try {
        if(BuildConfig.PLAY_BILLING_PUBLIC_KEY.isBlank() || JSONObject(p.originalJson).optString("packageName")!=app.packageName) false
        else {
            val key=KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(BuildConfig.PLAY_BILLING_PUBLIC_KEY)))
            listOf("SHA256withRSA","SHA1withRSA").any { algorithm -> Signature.getInstance(algorithm).run { initVerify(key); update(p.originalJson.toByteArray(Charsets.UTF_8)); verify(Base64.getDecoder().decode(p.signature)) } }
        }
    } catch (_: Exception) { false }
    private fun handlePurchases(purchases: List<Purchase>, authoritative: Boolean=false) { handler.post { if(!closed) applyPurchases(purchases,authoritative) } }
    private fun networkValidated(): Boolean {
        val manager=app.getSystemService(ConnectivityManager::class.java)
        return manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true
    }
    private fun applyPurchases(purchases: List<Purchase>, authoritative: Boolean) {
        pending=purchases.any { PRODUCT in it.products && it.purchaseState==Purchase.PurchaseState.PENDING }
        val owned=purchases.filter { PRODUCT in it.products && it.purchaseState==Purchase.PurchaseState.PURCHASED && verified(it) }
        if(owned.isEmpty()) { if(authoritative && networkValidated()) { lease=0; persist() }; update(); return }
        for(p in owned) {
            fun grant() { if(networkValidated()) { lease=now()+24*60*60*1000; persist() }; update() }
            if(p.isAcknowledged) grant() else client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { result -> handler.post { if(!closed) { if(result.responseCode==0) grant() else update() } } }
        }
    }
    private fun offer()=product?.subscriptionOfferDetails?.firstOrNull { it.basePlanId=="monthly" && it.offerId==null && it.pricingPhases.pricingPhaseList.any { phase -> phase.billingPeriod=="P1M" && phase.recurrenceMode==ProductDetails.RecurrenceMode.INFINITE_RECURRING } }
    private fun buy(activity: Activity) {
        val p=product ?: return; val offer=offer() ?: return
        if(BuildConfig.PLAY_BILLING_PUBLIC_KEY.isBlank()) return
        client.launchBillingFlow(activity,BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(p).setOfferToken(offer.offerToken).build())).build())
    }
    fun show(activity: Activity) {
        if(dialog?.isShowing==true)return
        val layout=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(36,20,36,20) }
        val info=TextView(activity); layout.addView(info)
        layout.addView(TextView(activity).apply { setText(R.string.access_reference_price) })
        val buy=Button(activity).apply { isAllCaps=false }; layout.addView(buy); buy.setOnClickListener { buy(activity) }
        val availability=TextView(activity);layout.addView(availability)
        val restore=Button(activity).apply { isAllCaps=false; setText(R.string.access_restore);setOnClickListener { refresh() } };layout.addView(restore)
        val manage=Button(activity).apply { isAllCaps=false; setText(R.string.access_manage);setOnClickListener { activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://play.google.com/store/account/subscriptions?sku=$PRODUCT&package=${app.packageName}"))) } };layout.addView(manage)
        val code=EditText(activity).apply { setHint(R.string.access_code) };layout.addView(code)
        val redeem=Button(activity).apply { isAllCaps=false; setText(R.string.access_redeem);setOnClickListener {
            if(BuildConfig.TEST_CODES && code.text.toString().startsWith("ST3.")) {
                val ok=redeemTest(code.text.toString().trim());Toast.makeText(activity,if(ok) R.string.access_extended else R.string.access_invalid_code,Toast.LENGTH_LONG).show();update()
            } else {
                val uri=Uri.parse("https://play.google.com/redeem?code="+Uri.encode(code.text.toString().trim()))
                try { activity.startActivity(Intent(Intent.ACTION_VIEW,uri).setPackage("com.android.vending")) }
                catch (_: android.content.ActivityNotFoundException) { activity.startActivity(Intent(Intent.ACTION_VIEW,uri)) }
            }
        } };layout.addView(redeem)
        if(BuildConfig.TEST_CODES) layout.addView(TextView(activity).apply { text=activity.getString(R.string.access_device,device);setTextIsSelectable(true) })
        layout.addView(TextView(activity).apply { setText(R.string.access_terms) })
        val d=AlertDialog.Builder(activity).setTitle(R.string.access_title).setView(ScrollView(activity).apply { addView(layout) })
            .setPositiveButton(R.string.access_continue,null).setNegativeButton(R.string.access_close) { _,_->activity.finish() }.create()
        dialog=d
        redraw={
            val price=offer()?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice
            val hours=maxOf(0,(trialUntil-now()+3_599_999)/3_600_000)
            info.text=if(lease>now()) activity.getString(R.string.access_subscribed) else activity.getString(if(allowed()) R.string.access_available else R.string.access_expired,hours)
            buy.text=activity.getString(R.string.access_subscribe,price ?: "US$2.99")
            availability.text=if(pending) activity.getString(R.string.access_pending) else if(price==null || BuildConfig.PLAY_BILLING_PUBLIC_KEY.isBlank()) activity.getString(R.string.access_unavailable) else ""
            buy.isEnabled=price!=null && BuildConfig.PLAY_BILLING_PUBLIC_KEY.isNotBlank() && !pending && storageOK && lease<=now()
            d.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled=allowed()
            d.setCancelable(allowed() && !needsIntroduction())
        }
        d.setOnShowListener { redraw?.invoke();d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { if(allowed()) { prefs.edit().putBoolean("introduced",true).commit();d.dismiss() } } }
        d.setOnDismissListener { dialog=null; redraw=null }
        d.show();refresh()
    }
    private fun redeemTest(code: String): Boolean = try {
        if(!BuildConfig.TEST_CODES || !app.packageName.endsWith(".tester") || !clockValid() || code.length>4096) false else {
            val parts=code.split('.');require(parts.size==3 && parts[0]=="ST3")
            val bytes=Base64.getUrlDecoder().decode(parts[1]);require(verifyTestSignature(bytes,Base64.getUrlDecoder().decode(parts[2]),TEST_PUBLIC_KEY))
            val p=JSONObject(String(bytes,Charsets.UTF_8))
            require(p.optInt("v")==1 && validTestGrant(p.getString("device"),p.getString("aud"),p.getString("nonce"),p.getLong("issued"),p.getLong("redeem_before"),p.getLong("seconds"),device,"android",used,now()/1000))
            val oldUntil=trialUntil;val oldUsed=used
            trialUntil=extendedTrial(now(),trialUntil);used=used+p.getString("nonce")
            if(persist()) true else { trialUntil=oldUntil;used=oldUsed;false }
        }
    } catch (_: Exception) { false }
    override fun close(){ closed=true;handler.removeCallbacksAndMessages(null);dialog?.dismiss();client.endConnection() }
    companion object {
        fun cachedAllowed(context: Context): Boolean {
            val p=context.applicationContext.getSharedPreferences("subscription-v1",Context.MODE_PRIVATE)
            val now=System.currentTimeMillis();val seen=p.getLong("seen",now)
            return accessAllowed(now,p.getLong("trialUntil",0),p.getLong("storeUntil",0),now+300_000>=seen)
        }
        const val PRODUCT="shottracer_monthly"
        const val TEST_PUBLIC_KEY="MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEqWZYqtctcRgRwJK/F7BkEB9j35lGhap5LqaOAlQGV6IpH3Fo3Av0i3lRaUKpqYK1zFb3wJ+lrmbkUo7fuR9tBw=="
    }
}
