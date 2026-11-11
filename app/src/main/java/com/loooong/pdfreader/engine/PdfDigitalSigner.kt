package com.loooong.pdfreader.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.SecurityProvider
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.visible.PDVisibleSigProperties
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.visible.PDVisibleSignDesigner
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x500.style.IETFUtils
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.CMSTypedData
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Provider
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class PdfSignaturePlacement {
    INVISIBLE,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
}

data class PdfPkcs12EntryInfo(
    val alias: String,
    val subject: String,
    val issuer: String,
    val serialNumber: String,
    val keyAlgorithm: String,
    val notBefore: Long,
    val notAfter: Long,
    val statusNow: PdfCertificateStatus,
    val allowsDigitalSignature: Boolean,
)

data class PdfPkcs12Info(
    val entries: List<PdfPkcs12EntryInfo>,
)

data class PdfDigitalSignRequest(
    val alias: String,
    val pageIndex: Int,
    val reason: String,
    val location: String,
    val contactInfo: String,
    val placement: PdfSignaturePlacement,
)

data class PdfDigitalSignResult(
    val signerName: String,
    val signatureCount: Int,
    val outputBytes: Long,
)

/** long: 正式数字签名必须始终走增量写入；普通 document.save() 会重排旧字节并破坏已有 ByteRange。 */
object PdfDigitalSigner {

    fun inspectPkcs12(bytes: ByteArray, password: String): PdfPkcs12Info {
        require(bytes.isNotEmpty()) { "证书文件为空" }
        val pin = password.toCharArray()
        return try {
            val provider = SecurityProvider.getProvider()
            val keyStore = loadKeyStore(bytes, pin, provider)
            val entries = keyStore.aliases().toList().mapNotNull { alias ->
                if (!keyStore.isKeyEntry(alias)) return@mapNotNull null
                val certificate = keyStore.getCertificate(alias) as? X509Certificate ?: return@mapNotNull null
                val key = runCatching { keyStore.getKey(alias, pin) }.getOrNull()
                if (key !is PrivateKey) return@mapNotNull null
                PdfPkcs12EntryInfo(
                    alias = alias,
                    subject = certificate.subjectX500Principal.name,
                    issuer = certificate.issuerX500Principal.name,
                    serialNumber = certificate.serialNumber.toString(16).uppercase(Locale.ROOT),
                    keyAlgorithm = key.algorithm,
                    notBefore = certificate.notBefore.time,
                    notAfter = certificate.notAfter.time,
                    statusNow = certificateStatus(certificate, Date()),
                    allowsDigitalSignature = certificate.allowsDocumentSigning(),
                )
            }.sortedBy(PdfPkcs12EntryInfo::alias)
            require(entries.isNotEmpty()) { "证书文件中没有可用的私钥条目" }
            PdfPkcs12Info(entries)
        } catch (error: Throwable) {
            throw IllegalArgumentException(error.pkcs12Message(), error)
        } finally {
            pin.fill('\u0000')
        }
    }

    fun sign(
        sourceFile: File,
        outputFile: File,
        documentPassword: String?,
        pkcs12Bytes: ByteArray,
        pkcs12Password: CharArray,
        request: PdfDigitalSignRequest,
    ): PdfDigitalSignResult {
        require(sourceFile.isFile) { "待签名 PDF 不存在" }
        require(sourceFile.canonicalFile != outputFile.canonicalFile) { "签名输出不能覆盖输入文件" }
        require(pkcs12Bytes.isNotEmpty()) { "证书文件为空" }
        val existingReport = PdfDigitalSignatureInspector.inspect(sourceFile, documentPassword)
        val unsafeExistingSignature = existingReport.signatures.firstOrNull {
            it.status == PdfDigitalSignatureStatus.INVALID ||
                it.status == PdfDigitalSignatureStatus.ERROR ||
                it.status == PdfDigitalSignatureStatus.UNSUPPORTED
        }
        require(unsafeExistingSignature == null) {
            "当前工作版本已有数字签名无法安全验证，请保留原文件并另行处理后再签署"
        }

        // long: 调用方持有的密码数组可能在事务结束时清零，这里复制一份供后台签署并在 finally 中销毁。
        val pin = pkcs12Password.copyOf()
        var signatureOptions: SignatureOptions? = null
        var appearanceBitmap: Bitmap? = null
        try {
            val provider = SecurityProvider.getProvider()
            val keyStore = loadKeyStore(pkcs12Bytes, pin, provider)
            val privateKey = keyStore.getKey(request.alias, pin) as? PrivateKey
                ?: error("所选证书条目没有可用私钥")
            val certificateChain = keyStore.getCertificateChain(request.alias)
                ?.mapNotNull { it as? X509Certificate }
                .orEmpty()
            require(certificateChain.isNotEmpty()) { "所选证书条目没有证书链" }
            val signerCertificate = certificateChain.first()
            require(certificateStatus(signerCertificate, Date()) == PdfCertificateStatus.VALID) {
                "所选证书当前不在有效期内"
            }
            require(signerCertificate.allowsDocumentSigning()) { "所选证书不允许数字签名用途" }

            outputFile.parentFile?.mkdirs()
            if (outputFile.exists() && !outputFile.delete()) error("无法准备签名输出文件")
            PDDocument.load(sourceFile, documentPassword.orEmpty()).use { document ->
                // long: 当前流程会新增签名字段和可视外观，只有具备文档修改权限时才允许继续，
                // 避免调用方绕过编辑模式后修改只读加密 PDF。
                require(document.currentAccessPermission.canModify()) {
                    "当前 PDF 权限不允许添加数字签名"
                }
                require(request.pageIndex in 0 until document.numberOfPages) { "可视签名目标页超出范围" }
                require(docMdpPermission(document) != 1) { "当前认证 PDF 禁止任何签名后修改" }
                val signerName = certificateCommonName(signerCertificate)
                val signature = PDSignature().apply {
                    setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                    setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED)
                    setName(signerName)
                    request.reason.trim().takeIf(String::isNotEmpty)?.let(::setReason)
                    request.location.trim().takeIf(String::isNotEmpty)?.let(::setLocation)
                    request.contactInfo.trim().takeIf(String::isNotEmpty)?.let(::setContactInfo)
                    setSignDate(Calendar.getInstance())
                }
                val preferredSize = preferredSignatureSize(certificateChain)
                if (request.placement == PdfSignaturePlacement.INVISIBLE) {
                    signatureOptions = SignatureOptions().apply {
                        setPage(request.pageIndex)
                        setPreferredSignatureSize(preferredSize)
                    }
                } else {
                    appearanceBitmap = createAppearanceBitmap(signerName, signature.signDate?.time, request.reason)
                    signatureOptions = createVisibleSignatureOptions(
                        document = document,
                        pageIndex = request.pageIndex,
                        placement = request.placement,
                        bitmap = requireNotNull(appearanceBitmap),
                        signerName = signerName,
                        reason = request.reason,
                        location = request.location,
                        preferredSize = preferredSize,
                    )
                }
                document.addSignature(signature, requireNotNull(signatureOptions))
                FileOutputStream(outputFile).buffered().use { output ->
                    val externalSigning = document.saveIncrementalForExternalSigning(output)
                    val cms = createCmsSignature(
                        content = externalSigning.content,
                        privateKey = privateKey,
                        certificateChain = certificateChain,
                        provider = provider,
                    )
                    require(cms.size <= preferredSize) {
                        "证书链生成的签名超过预留空间，请缩短证书链后重试"
                    }
                    externalSigning.setSignature(cms)
                }
            }

            val verified = PdfDigitalSignatureInspector.inspect(outputFile, documentPassword)
            val newest = verified.signatures.maxByOrNull { it.signedRevisionLength ?: -1L }
                ?: error("签名输出没有生成签名字典")
            check(newest.status == PdfDigitalSignatureStatus.VALID) {
                "签名输出回读验证失败：${newest.statusMessage}"
            }
            return PdfDigitalSignResult(
                signerName = newest.name ?: request.alias,
                signatureCount = verified.signatures.size,
                outputBytes = outputFile.length(),
            )
        } catch (error: Throwable) {
            runCatching { outputFile.delete() }
            throw error
        } finally {
            runCatching { signatureOptions?.close() }
            appearanceBitmap?.recycle()
            pin.fill('\u0000')
        }
    }

    private fun loadKeyStore(bytes: ByteArray, pin: CharArray, provider: Provider): KeyStore =
        KeyStore.getInstance("PKCS12", provider).apply {
            ByteArrayInputStream(bytes).use { load(it, pin) }
        }

    private fun createCmsSignature(
        content: InputStream,
        privateKey: PrivateKey,
        certificateChain: List<X509Certificate>,
        provider: Provider,
    ): ByteArray {
        val signatureAlgorithm = when (privateKey.algorithm.uppercase(Locale.ROOT)) {
            "RSA" -> "SHA256withRSA"
            "EC", "ECDSA" -> "SHA256withECDSA"
            "DSA" -> "SHA256withDSA"
            else -> error("暂不支持 ${privateKey.algorithm} 私钥算法")
        }
        val contentSigner = JcaContentSignerBuilder(signatureAlgorithm)
            .setProvider(provider)
            .build(privateKey)
        val digestProvider = JcaDigestCalculatorProviderBuilder().setProvider(provider).build()
        return CMSSignedDataGenerator().apply {
            addSignerInfoGenerator(
                JcaSignerInfoGeneratorBuilder(digestProvider).build(contentSigner, certificateChain.first()),
            )
            addCertificates(JcaCertStore(certificateChain))
        }.generate(InputStreamTypedData(content), false).encoded
    }

    private fun createVisibleSignatureOptions(
        document: PDDocument,
        pageIndex: Int,
        placement: PdfSignaturePlacement,
        bitmap: Bitmap,
        signerName: String,
        reason: String,
        location: String,
        preferredSize: Int,
    ): SignatureOptions {
        val page = document.getPage(pageIndex)
        val crop = page.cropBox
        val width = minOf(VISIBLE_SIGNATURE_WIDTH, crop.width - VISIBLE_SIGNATURE_MARGIN * 2f).coerceAtLeast(72f)
        val height = minOf(VISIBLE_SIGNATURE_HEIGHT, crop.height - VISIBLE_SIGNATURE_MARGIN * 2f).coerceAtLeast(36f)
        val x = when (placement) {
            PdfSignaturePlacement.TOP_RIGHT,
            PdfSignaturePlacement.BOTTOM_RIGHT,
            -> (crop.width - width - VISIBLE_SIGNATURE_MARGIN).coerceAtLeast(VISIBLE_SIGNATURE_MARGIN)
            else -> VISIBLE_SIGNATURE_MARGIN
        }
        val y = when (placement) {
            PdfSignaturePlacement.BOTTOM_LEFT,
            PdfSignaturePlacement.BOTTOM_RIGHT,
            -> (crop.height - height - VISIBLE_SIGNATURE_MARGIN).coerceAtLeast(VISIBLE_SIGNATURE_MARGIN)
            else -> VISIBLE_SIGNATURE_MARGIN
        }
        val designer = PDVisibleSignDesigner(document, bitmap, pageIndex + 1)
            .coordinates(x, y)
            .width(width)
            .height(height)
            .signatureFieldName("ReaderSignature-${System.currentTimeMillis()}")
            .adjustForRotation()
        val properties = PDVisibleSigProperties()
            .signerName(signerName)
            .signerLocation(location.trim())
            .signatureReason(reason.trim())
            .preferredSize(preferredSize)
            .page(pageIndex + 1)
            .visualSignEnabled(true)
            .setPdVisibleSignature(designer)
        properties.buildSignature()
        return SignatureOptions().apply {
            setPage(pageIndex)
            setPreferredSignatureSize(preferredSize)
            setVisualSignature(properties)
        }
    }

    private fun createAppearanceBitmap(signerName: String, signDate: Date?, reason: String): Bitmap {
        val bitmap = Bitmap.createBitmap(900, 300, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(24, 113, 79)
            style = Paint.Style.FILL
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(88, 98, 112)
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawRect(2f, 2f, bitmap.width - 2f, bitmap.height - 2f, border)
        canvas.drawRect(0f, 0f, 18f, bitmap.height.toFloat(), accent)
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(24, 30, 38)
            textSize = 48f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(63, 71, 83)
            textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        canvas.drawText("数字签名", 48f, 70f, title)
        canvas.drawText(ellipsize(signerName, body, 800f), 48f, 132f, body)
        val date = signDate?.let {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(it)
        }.orEmpty()
        canvas.drawText("签署时间 $date", 48f, 190f, body)
        reason.trim().takeIf(String::isNotEmpty)?.let {
            canvas.drawText("原因 ${ellipsize(it, body, 760f)}", 48f, 248f, body)
        }
        return bitmap
    }

    private fun ellipsize(text: String, paint: Paint, width: Float): String {
        if (paint.measureText(text) <= width) return text
        val suffix = "…"
        val count = paint.breakText(text, true, (width - paint.measureText(suffix)).coerceAtLeast(0f), null)
        return text.take(count.coerceAtLeast(0)) + suffix
    }

    private fun preferredSignatureSize(chain: List<X509Certificate>): Int {
        val certificateBytes = chain.sumOf { runCatching { it.encoded.size }.getOrDefault(0) }
        return (certificateBytes * 3 + 32 * 1024)
            .coerceAtLeast(SignatureOptions.DEFAULT_SIGNATURE_SIZE)
            .coerceAtMost(MAX_SIGNATURE_SIZE)
    }

    private fun certificateCommonName(certificate: X509Certificate): String = runCatching {
        val name = X500Name(certificate.subjectX500Principal.name)
        val rdn = name.getRDNs(BCStyle.CN).firstOrNull() ?: return@runCatching null
        IETFUtils.valueToString(rdn.first.value)
    }.getOrNull()?.takeIf(String::isNotBlank)
        ?: certificate.subjectX500Principal.name

    private fun X509Certificate.allowsDocumentSigning(): Boolean {
        val usage = keyUsage ?: return true
        return usage.getOrNull(0) == true || usage.getOrNull(1) == true
    }

    private fun certificateStatus(certificate: X509Certificate, date: Date): PdfCertificateStatus = try {
        certificate.checkValidity(date)
        PdfCertificateStatus.VALID
    } catch (_: java.security.cert.CertificateExpiredException) {
        PdfCertificateStatus.EXPIRED
    } catch (_: java.security.cert.CertificateNotYetValidException) {
        PdfCertificateStatus.NOT_YET_VALID
    } catch (_: Throwable) {
        PdfCertificateStatus.INVALID
    }

    private fun docMdpPermission(document: PDDocument): Int {
        val catalog = document.documentCatalog.cosObject
        val permissions = catalog.getDictionaryObject(COSName.getPDFName("Perms")) as? COSDictionary ?: return 0
        val docMdp = permissions.getDictionaryObject(COSName.getPDFName("DocMDP")) as? COSDictionary ?: return 0
        val references = docMdp.getDictionaryObject(COSName.getPDFName("Reference")) as? COSArray ?: return 0
        for (index in 0 until references.size()) {
            val reference = references.getObject(index) as? COSDictionary ?: continue
            if (reference.getNameAsString(COSName.getPDFName("TransformMethod")) != "DocMDP") continue
            val parameters = reference.getDictionaryObject(COSName.getPDFName("TransformParams")) as? COSDictionary
            return parameters?.getInt(COSName.getPDFName("P"), 2) ?: 2
        }
        return 0
    }

    private fun Throwable.pkcs12Message(): String {
        val detail = generateSequence(this) { it.cause }
            .mapNotNull { error -> error.message }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        return when {
            detail.contains("password", ignoreCase = true) ||
                detail.contains("mac invalid", ignoreCase = true) -> "证书密码错误或 PKCS#12 文件损坏"
            detail.isNotBlank() -> "无法读取 PKCS#12 证书：$detail"
            else -> "无法读取 PKCS#12 证书"
        }
    }

    private class InputStreamTypedData(private val input: InputStream) : CMSTypedData {
        override fun getContentType() = CMSObjectIdentifiers.data

        override fun write(out: OutputStream) {
            input.copyTo(out)
        }

        override fun getContent(): Any = input
    }

    private const val VISIBLE_SIGNATURE_WIDTH = 190f
    private const val VISIBLE_SIGNATURE_HEIGHT = 68f
    private const val VISIBLE_SIGNATURE_MARGIN = 24f
    private const val MAX_SIGNATURE_SIZE = 1024 * 1024
}
