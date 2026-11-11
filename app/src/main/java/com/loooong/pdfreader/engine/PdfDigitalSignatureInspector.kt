package com.loooong.pdfreader.engine

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.SecurityProvider
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import org.bouncycastle.asn1.cms.CMSAttributes
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cms.CMSProcessable
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSException
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.operator.OperatorCreationException
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import java.util.Locale

/** long: 签名密码学结果与文档覆盖范围分开建模，避免“签名有效但文档后来被修改”被误报成完全有效。 */
enum class PdfDigitalSignatureStatus {
    VALID,
    VALID_WITH_LATER_CHANGES,
    INVALID,
    UNSUPPORTED,
    ERROR,
}

enum class PdfSignatureCoverage {
    WHOLE_DOCUMENT,
    SIGNED_REVISION,
    INVALID,
}

enum class PdfCertificateStatus {
    VALID,
    EXPIRED,
    NOT_YET_VALID,
    INVALID,
    UNKNOWN,
}

enum class PdfCertificateChainStatus {
    EMBEDDED_CHAIN_VALID,
    SELF_SIGNED,
    INCOMPLETE,
    INVALID,
    NOT_AVAILABLE,
}

data class PdfCertificateInfo(
    val subject: String,
    val issuer: String,
    val serialNumber: String,
    val notBefore: Long?,
    val notAfter: Long?,
    val statusAtSigningTime: PdfCertificateStatus,
    val statusNow: PdfCertificateStatus,
    val chainLength: Int,
    val chainStatus: PdfCertificateChainStatus,
)

data class PdfDigitalSignatureInfo(
    val index: Int,
    val fieldName: String?,
    val name: String?,
    val reason: String?,
    val location: String?,
    val contactInfo: String?,
    val signDate: Long?,
    val filter: String?,
    val subFilter: String?,
    val digestAlgorithm: String?,
    val encryptionAlgorithm: String?,
    val byteRange: List<Long>,
    val signedRevisionLength: Long?,
    val fileLength: Long,
    val coverage: PdfSignatureCoverage,
    val status: PdfDigitalSignatureStatus,
    val statusMessage: String,
    val signerCount: Int,
    val certificate: PdfCertificateInfo?,
)

data class PdfDigitalSignatureReport(
    val fileName: String,
    val signatures: List<PdfDigitalSignatureInfo>,
)

/**
 * long: 从当前 PDF 字节副本重新解析数字签名，确保界面展示的是用户将要保存的版本，
 * 而不是仍停留在内存中的签名字典快照。
 */
object PdfDigitalSignatureInspector {

    fun inspect(file: File, password: String? = null): PdfDigitalSignatureReport {
        require(file.isFile) { "签名检查目标不存在" }
        val provider = SecurityProvider.getProvider()
        PDDocument.load(file, password.orEmpty()).use { document ->
            val fieldsBySignature = signatureFieldNames(document)
            val signatures = document.signatureDictionaries.mapIndexed { index, signature ->
                inspectSignature(
                    file = file,
                    signature = signature,
                    index = index,
                    fieldName = fieldsBySignature[signature.cosObject],
                    provider = provider,
                )
            }
            return PdfDigitalSignatureReport(file.name, signatures)
        }
    }

    private fun signatureFieldNames(document: PDDocument): Map<com.tom_roush.pdfbox.cos.COSDictionary, String?> {
        val form = document.documentCatalog.acroForm ?: return emptyMap()
        return buildMap {
            form.fieldTree.filterIsInstance<com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField>()
                .forEach { field ->
                    val signature = runCatching { field.signature }.getOrNull() ?: return@forEach
                    put(signature.cosObject, field.fullyQualifiedName ?: field.partialName)
                }
        }
    }

    private fun inspectSignature(
        file: File,
        signature: PDSignature,
        index: Int,
        fieldName: String?,
        provider: java.security.Provider,
    ): PdfDigitalSignatureInfo {
        val fileLength = file.length()
        val rawRange = signature.byteRange?.map { it.toLong() }.orEmpty()
        val range = validateByteRange(rawRange, fileLength)
        val coverage = coverageFor(range, fileLength)
        val common = PdfDigitalSignatureInfo(
            index = index,
            fieldName = fieldName,
            name = signature.name,
            reason = signature.reason,
            location = signature.location,
            contactInfo = signature.contactInfo,
            signDate = signature.signDate?.time?.time,
            filter = signature.filter,
            subFilter = signature.subFilter,
            digestAlgorithm = null,
            encryptionAlgorithm = null,
            byteRange = rawRange,
            signedRevisionLength = range?.let { it[2] + it[3] },
            fileLength = fileLength,
            coverage = coverage,
            status = PdfDigitalSignatureStatus.ERROR,
            statusMessage = "",
            signerCount = 0,
            certificate = null,
        )
        if (range == null) {
            return common.copy(
                coverage = PdfSignatureCoverage.INVALID,
                status = PdfDigitalSignatureStatus.INVALID,
                statusMessage = "ByteRange 不完整或超出当前文件范围",
            )
        }
        val contents = runCatching { signature.getContents() }.getOrNull()
        if (contents == null || contents.isEmpty()) {
            return common.copy(
                status = PdfDigitalSignatureStatus.INVALID,
                statusMessage = "签名字典没有可验证的 Contents",
            )
        }
        val contentLayoutError = validateContentsLayout(file, range, contents)
        if (contentLayoutError != null) {
            return common.copy(
                status = PdfDigitalSignatureStatus.INVALID,
                statusMessage = contentLayoutError,
            )
        }

        return try {
            when (signature.subFilter?.lowercase(Locale.ROOT)) {
                "adbe.pkcs7.detached", "etsi.cades.detached" ->
                    verifyDetached(file, range, contents, signature, common, provider)
                "adbe.pkcs7.sha1" ->
                    verifySha1(file, range, contents, common, provider)
                "adbe.x509.rsa_sha1" ->
                    verifyX509RsaSha1(file, range, contents, signature, common, provider)
                else -> common.copy(
                    status = PdfDigitalSignatureStatus.UNSUPPORTED,
                    statusMessage = "暂不支持 ${signature.subFilter ?: "未知"} 签名格式",
                )
            }
        } catch (error: CMSException) {
            common.copy(
                status = PdfDigitalSignatureStatus.INVALID,
                statusMessage = "签名数据校验失败：${error.message.orEmpty().ifBlank { "摘要或签名值不匹配" }}",
            )
        } catch (error: Throwable) {
            common.copy(
                status = PdfDigitalSignatureStatus.ERROR,
                statusMessage = error.userFacingSignatureMessage(),
            )
        }
    }

    private fun verifyDetached(
        file: File,
        range: LongArray,
        contents: ByteArray,
        signature: PDSignature,
        common: PdfDigitalSignatureInfo,
        provider: java.security.Provider,
    ): PdfDigitalSignatureInfo {
        val processable = FileByteRangeProcessable(file, range)
        val signedData = CMSSignedData(processable, contents)
        val signers = signedData.signerInfos.signers.toList()
        if (signers.isEmpty()) return common.copy(
            status = PdfDigitalSignatureStatus.INVALID,
            statusMessage = "CMS 中没有签署者",
        )
        val holders = signedData.certificates.getMatches(null)
            .mapNotNull { it as? X509CertificateHolder }
        if (holders.isEmpty()) return common.copy(
            status = PdfDigitalSignatureStatus.INVALID,
            statusMessage = "CMS 中没有嵌入证书",
            signerCount = signers.size,
        )
        val converter = JcaX509CertificateConverter().setProvider(provider)
        val signerCertificates = signers.mapNotNull { signer ->
            holders.firstOrNull { holder -> holderMatches(holder, signer.sid) }
                ?.let { holder -> runCatching { converter.getCertificate(holder) }.getOrNull() }
        }
        if (signerCertificates.size != signers.size) return common.copy(
            status = PdfDigitalSignatureStatus.INVALID,
            statusMessage = "签署者与嵌入证书无法匹配",
            signerCount = signers.size,
        )
        val verifierBuilder = JcaSimpleSignerInfoVerifierBuilder().setProvider(provider)
        val verified = signers.zip(signerCertificates).all { (signer, certificate) ->
            signer.verify(verifierBuilder.build(certificate))
        }
        val firstCertificate = signerCertificates.first()
        val signingDate = signature.signDate?.time ?: extractSigningTime(signers.first())
        val certificateInfo = certificateInfo(
            certificates = holders.mapNotNull { runCatching { converter.getCertificate(it) }.getOrNull() },
            leaf = firstCertificate,
            signingDate = signingDate,
            provider = provider,
        )
        val status = when {
            !verified -> PdfDigitalSignatureStatus.INVALID
            common.coverage == PdfSignatureCoverage.SIGNED_REVISION -> PdfDigitalSignatureStatus.VALID_WITH_LATER_CHANGES
            else -> PdfDigitalSignatureStatus.VALID
        }
        return common.copy(
            digestAlgorithm = signers.first().digestAlgOID,
            encryptionAlgorithm = signers.first().encryptionAlgOID,
            signDate = signingDate?.time,
            status = status,
            statusMessage = if (verified) {
                if (status == PdfDigitalSignatureStatus.VALID_WITH_LATER_CHANGES) {
                    "签名密码学验证通过，但文件包含签名之后的增量修改"
                } else {
                    "签名密码学验证通过"
                }
            } else {
                "签名校验失败，签名内容或文件范围可能已被修改"
            },
            signerCount = signers.size,
            certificate = certificateInfo,
        )
    }

    private fun verifySha1(
        file: File,
        range: LongArray,
        contents: ByteArray,
        common: PdfDigitalSignatureInfo,
        provider: java.security.Provider,
    ): PdfDigitalSignatureInfo {
        val digest = MessageDigest.getInstance("SHA-1")
        FileByteRangeProcessable(file, range).write(DigestOutput(digest))
        val signedData = CMSSignedData(CMSProcessableByteArray(digest.digest()), contents)
        val signers = signedData.signerInfos.signers.toList()
        val holders = signedData.certificates.getMatches(null).mapNotNull { it as? X509CertificateHolder }
        val converter = JcaX509CertificateConverter().setProvider(provider)
        val signer = signers.firstOrNull()
        val holder = signer?.let { candidate -> holders.firstOrNull { holder -> holderMatches(holder, candidate.sid) } }
        val certificate = holder?.let { runCatching { converter.getCertificate(it) }.getOrNull() }
        val verified = signer != null && certificate != null &&
            signer.verify(JcaSimpleSignerInfoVerifierBuilder().setProvider(provider).build(certificate))
        val status = when {
            !verified -> PdfDigitalSignatureStatus.INVALID
            common.coverage == PdfSignatureCoverage.SIGNED_REVISION -> PdfDigitalSignatureStatus.VALID_WITH_LATER_CHANGES
            else -> PdfDigitalSignatureStatus.VALID
        }
        return common.copy(
            digestAlgorithm = signer?.digestAlgOID ?: "SHA-1",
            encryptionAlgorithm = signer?.encryptionAlgOID,
            status = status,
            statusMessage = if (verified) {
                if (status == PdfDigitalSignatureStatus.VALID_WITH_LATER_CHANGES) {
                    "SHA-1 签名验证通过，但文件包含签名之后的增量修改"
                } else {
                    "SHA-1 签名验证通过"
                }
            } else {
                "SHA-1 签名校验失败"
            },
            signerCount = signers.size,
            certificate = certificate?.let {
                certificateInfo(listOf(it), it, common.signDate?.let(::Date), provider)
            },
        )
    }

    private fun verifyX509RsaSha1(
        file: File,
        range: LongArray,
        contents: ByteArray,
        signature: PDSignature,
        common: PdfDigitalSignatureInfo,
        provider: java.security.Provider,
    ): PdfDigitalSignatureInfo {
        val certificate = readX509Certificate(signature) ?: return common.copy(
            status = PdfDigitalSignatureStatus.INVALID,
            statusMessage = "RSA 签名缺少 X.509 证书",
        )
        val verifier = Signature.getInstance("SHA1withRSA", provider)
        verifier.initVerify(certificate)
        FileByteRangeProcessable(file, range).write(object : OutputStream() {
            override fun write(value: Int) = verifier.update(value.toByte())
            override fun write(bytes: ByteArray, offset: Int, length: Int) = verifier.update(bytes, offset, length)
        })
        val verified = verifier.verify(contents)
        val status = when {
            !verified -> PdfDigitalSignatureStatus.INVALID
            common.coverage == PdfSignatureCoverage.SIGNED_REVISION -> PdfDigitalSignatureStatus.VALID_WITH_LATER_CHANGES
            else -> PdfDigitalSignatureStatus.VALID
        }
        return common.copy(
            digestAlgorithm = "SHA-1",
            encryptionAlgorithm = "RSA",
            status = status,
            statusMessage = if (verified) {
                if (status == PdfDigitalSignatureStatus.VALID_WITH_LATER_CHANGES) {
                    "RSA 签名验证通过，但文件包含签名之后的增量修改"
                } else {
                    "RSA 签名验证通过"
                }
            } else {
                "RSA 签名校验失败"
            },
            signerCount = 1,
            certificate = certificateInfo(listOf(certificate), certificate, signature.signDate?.time, provider),
        )
    }

    private fun readX509Certificate(signature: PDSignature): X509Certificate? {
        val certObject = signature.cosObject.getDictionaryObject(COSName.CERT)
        val bytes = when (certObject) {
            is COSString -> certObject.bytes
            is COSArray -> (0 until certObject.size()).asSequence()
                .mapNotNull { certObject.getObject(it) as? COSString }
                .map(COSString::getBytes)
                .firstOrNull()
            else -> null
        } ?: return null
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(bytes)) as? X509Certificate
    }

    private fun certificateInfo(
        certificates: List<X509Certificate>,
        leaf: X509Certificate,
        signingDate: Date?,
        provider: java.security.Provider,
    ): PdfCertificateInfo {
        val atSigning = certificateStatus(leaf, signingDate)
        val now = certificateStatus(leaf, Date())
        val chain = embeddedChainStatus(certificates, leaf, signingDate, provider)
        return PdfCertificateInfo(
            subject = leaf.subjectX500Principal.name,
            issuer = leaf.issuerX500Principal.name,
            serialNumber = leaf.serialNumber.toString(16).uppercase(Locale.ROOT),
            notBefore = leaf.notBefore?.time,
            notAfter = leaf.notAfter?.time,
            statusAtSigningTime = atSigning,
            statusNow = now,
            chainLength = certificates.size,
            chainStatus = chain,
        )
    }

    private fun embeddedChainStatus(
        certificates: List<X509Certificate>,
        leaf: X509Certificate,
        signingDate: Date?,
        provider: java.security.Provider,
    ): PdfCertificateChainStatus {
        if (certificates.isEmpty()) return PdfCertificateChainStatus.NOT_AVAILABLE
        val bySubject = certificates.associateBy { it.subjectX500Principal }
        val visited = mutableSetOf<String>()
        var current = leaf
        var depth = 0
        while (depth++ <= certificates.size) {
            val key = current.subjectX500Principal.name + "|" + current.serialNumber
            if (!visited.add(key)) return PdfCertificateChainStatus.INVALID
            if (certificateStatus(current, signingDate) == PdfCertificateStatus.INVALID) {
                return PdfCertificateChainStatus.INVALID
            }
            if (current.subjectX500Principal == current.issuerX500Principal) {
                return runCatching {
                    current.verify(current.publicKey, provider)
                    if (depth == 1) PdfCertificateChainStatus.SELF_SIGNED
                    else PdfCertificateChainStatus.EMBEDDED_CHAIN_VALID
                }.getOrDefault(PdfCertificateChainStatus.INVALID)
            }
            val issuer = bySubject[current.issuerX500Principal]
                ?: return PdfCertificateChainStatus.INCOMPLETE
            if (runCatching { current.verify(issuer.publicKey, provider) }.isFailure) {
                return PdfCertificateChainStatus.INVALID
            }
            current = issuer
        }
        return PdfCertificateChainStatus.INVALID
    }

    private fun certificateStatus(certificate: X509Certificate, date: Date?): PdfCertificateStatus {
        if (date == null) return PdfCertificateStatus.UNKNOWN
        return try {
            certificate.checkValidity(date)
            PdfCertificateStatus.VALID
        } catch (_: java.security.cert.CertificateExpiredException) {
            PdfCertificateStatus.EXPIRED
        } catch (_: java.security.cert.CertificateNotYetValidException) {
            PdfCertificateStatus.NOT_YET_VALID
        } catch (_: GeneralSecurityException) {
            PdfCertificateStatus.INVALID
        }
    }

    private fun extractSigningTime(signer: org.bouncycastle.cms.SignerInformation): Date? = runCatching {
        val attribute = signer.signedAttributes?.get(CMSAttributes.signingTime) ?: return@runCatching null
        Time.getInstance(attribute.attrValues.getObjectAt(0)).date
    }.getOrNull()

    private fun holderMatches(holder: X509CertificateHolder, sid: org.bouncycastle.cms.SignerId): Boolean =
        (sid.serialNumber == null || sid.serialNumber == holder.serialNumber) &&
            (sid.issuer == null || sid.issuer == holder.issuer)

    private fun validateByteRange(raw: List<Long>, fileLength: Long): LongArray? {
        if (raw.size != 4) return null
        val range = raw.toLongArray()
        if (range.any { it < 0L }) return null
        if (range[0] != 0L || range[1] <= 0L || range[3] <= 0L) return null
        val firstEnd = range[0] + range[1]
        val secondEnd = range[2] + range[3]
        if (range[2] < firstEnd || secondEnd > fileLength || range[2] <= firstEnd) return null
        return range
    }

    private fun coverageFor(range: LongArray?, fileLength: Long): PdfSignatureCoverage {
        if (range == null) return PdfSignatureCoverage.INVALID
        return when (val signedEnd = range[2] + range[3]) {
            fileLength -> PdfSignatureCoverage.WHOLE_DOCUMENT
            in 0 until fileLength -> PdfSignatureCoverage.SIGNED_REVISION
            else -> PdfSignatureCoverage.INVALID
        }
    }

    private fun validateContentsLayout(file: File, range: LongArray, contents: ByteArray): String? {
        val gapLength = range[2] - range[1]
        if (gapLength < 2L) return "签名内容占位区长度异常"
        if (gapLength > MAX_SIGNATURE_GAP_BYTES) return "签名内容占位区超过安全检查上限"
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(range[1])
                if (raf.read() != '<'.code) return@use "签名内容不是 PDF 十六进制字符串"
                val encoded = ByteArray((gapLength - 2L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                raf.readFully(encoded)
                if (raf.read() != '>'.code) return@use "签名内容缺少结束分隔符"
                val hex = encoded.filterNot { it.toInt().toChar().isWhitespace() }.toByteArray()
                val expected = contents.toHexAscii()
                val actual = hex.toString(Charsets.US_ASCII)
                if (!actual.equals(expected, ignoreCase = true)) {
                    "文件中的签名内容与签名字典不一致"
                } else {
                    null
                }
            }
        }.getOrElse { "无法读取签名内容占位区：${it.message.orEmpty()}" }
    }

    private fun ByteArray.toHexAscii(): String = buildString(size * 2) {
        for (byte in this@toHexAscii) append("%02x".format(Locale.ROOT, byte.toInt() and 0xFF))
    }

    private fun Throwable.userFacingSignatureMessage(): String = when (this) {
        is OperatorCreationException -> "无法创建证书验证器"
        is IOException -> message?.takeIf { it.isNotBlank() } ?: "读取签名数据失败"
        else -> message?.takeIf { it.isNotBlank() } ?: "签名解析失败"
    }

    /** long: CMS 验证只把两个 ByteRange 片段写入摘要，避免大 PDF 产生第二份完整 byte[]。 */
    private class FileByteRangeProcessable(
        private val file: File,
        private val range: LongArray,
    ) : CMSProcessable {
        override fun write(out: OutputStream) {
            RandomAccessFile(file, "r").use { raf ->
                writeRange(raf, out, range[0], range[1])
                writeRange(raf, out, range[2], range[3])
            }
        }

        override fun getContent(): Any = this
    }

    private fun writeRange(raf: RandomAccessFile, out: OutputStream, start: Long, length: Long) {
        raf.seek(start)
        var remaining = length
        val buffer = ByteArray(32 * 1024)
        while (remaining > 0L) {
            val read = raf.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) throw IOException("签名 ByteRange 读取提前结束")
            out.write(buffer, 0, read)
            remaining -= read
        }
    }

    private class DigestOutput(private val digest: MessageDigest) : OutputStream() {
        override fun write(value: Int) = digest.update(value.toByte())
        override fun write(bytes: ByteArray, offset: Int, length: Int) = digest.update(bytes, offset, length)
    }

    private const val MAX_SIGNATURE_GAP_BYTES = 16L * 1024L * 1024L
}
