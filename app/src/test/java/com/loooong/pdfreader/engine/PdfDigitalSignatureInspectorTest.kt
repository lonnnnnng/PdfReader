package com.loooong.pdfreader.engine

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.SecurityProvider
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.CMSTypedData
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Provider
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date

class PdfDigitalSignatureInspectorTest {

    @Test
    fun verifiesDetachedCmsSignatureAndCertificate() {
        val signedFile = createSignedPdfFile()
        try {
            val report = PdfDigitalSignatureInspector.inspect(signedFile)
            assertEquals(1, report.signatures.size)
            val signature = report.signatures.single()
            assertEquals(PdfDigitalSignatureStatus.VALID, signature.status)
            assertEquals(PdfSignatureCoverage.WHOLE_DOCUMENT, signature.coverage)
            assertEquals("Test Signer", signature.name)
            assertEquals(1, signature.signerCount)
            assertNotNull(signature.certificate)
            assertEquals(PdfCertificateStatus.VALID, signature.certificate?.statusAtSigningTime)
            assertEquals(PdfCertificateChainStatus.SELF_SIGNED, signature.certificate?.chainStatus)
        } finally {
            signedFile.delete()
        }
    }

    @Test
    fun reportsValidSignatureWhenLaterIncrementalBytesExist() {
        val signedFile = createSignedPdfFile()
        val appendedFile = File.createTempFile("signed-appended-", ".pdf")
        try {
            appendedFile.outputStream().use { output ->
                signedFile.inputStream().use { it.copyTo(output) }
                output.write("\n% later revision marker\n".encodeToByteArray())
            }
            val signature = PdfDigitalSignatureInspector.inspect(appendedFile).signatures.single()
            assertEquals(PdfDigitalSignatureStatus.VALID_WITH_LATER_CHANGES, signature.status)
            assertEquals(PdfSignatureCoverage.SIGNED_REVISION, signature.coverage)
            assertTrue(signature.fileLength > requireNotNull(signature.signedRevisionLength))
        } finally {
            signedFile.delete()
            appendedFile.delete()
        }
    }

    @Test
    fun reportsInvalidWhenSignedBytesAreChanged() {
        val signedFile = createSignedPdfFile()
        val tamperedFile = File.createTempFile("signed-tampered-", ".pdf")
        try {
            val bytes = signedFile.readBytes()
            val marker = "Test Signer".encodeToByteArray()
            val markerIndex = bytes.indexOfSubArray(marker)
            assertTrue("测试文件应包含签署者名称", markerIndex >= 0)
            bytes[markerIndex] = 'B'.code.toByte()
            tamperedFile.writeBytes(bytes)

            val signature = PdfDigitalSignatureInspector.inspect(tamperedFile).signatures.single()
            assertEquals(PdfDigitalSignatureStatus.INVALID, signature.status)
            assertTrue(signature.statusMessage.contains("校验失败"))
        } finally {
            signedFile.delete()
            tamperedFile.delete()
        }
    }

    private fun createSignedPdfFile(): File {
        val provider = SecurityProvider.getProvider()
        val keyPair = KeyPairGenerator.getInstance("RSA", provider).apply { initialize(2048) }.generateKeyPair()
        val certificate = createCertificate(keyPair, provider)
        val unsignedBytes = PDDocument().use { document ->
            document.addPage(PDPage())
            ByteArrayOutputStream().also(document::save).toByteArray()
        }
        val signedBytes = PDDocument.load(unsignedBytes).use { document ->
            val signature = PDSignature().apply {
                setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED)
                setName("Test Signer")
                setReason("Automated verification test")
                setSignDate(Calendar.getInstance())
            }
            document.addSignature(
                signature,
                SignatureInterface { content -> createCmsSignature(content, keyPair, certificate, provider) },
            )
            ByteArrayOutputStream().also(document::saveIncremental).toByteArray()
        }
        return File.createTempFile("signed-valid-", ".pdf").apply { writeBytes(signedBytes) }
    }

    private fun createCertificate(keyPair: KeyPair, provider: Provider): X509Certificate {
        val now = System.currentTimeMillis()
        val subject = X500Name("CN=Test Signer,O=Reader Test")
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(now),
            Date(now - 60_000L),
            Date(now + 86_400_000L),
            subject,
            keyPair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(keyPair.private)
        return JcaX509CertificateConverter().setProvider(provider).getCertificate(builder.build(signer))
    }

    private fun createCmsSignature(
        content: InputStream,
        keyPair: KeyPair,
        certificate: X509Certificate,
        provider: Provider,
    ): ByteArray {
        val generator = CMSSignedDataGenerator()
        val digestProvider = JcaDigestCalculatorProviderBuilder().setProvider(provider).build()
        val contentSigner = JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(keyPair.private)
        generator.addSignerInfoGenerator(
            JcaSignerInfoGeneratorBuilder(digestProvider).build(contentSigner, certificate),
        )
        generator.addCertificates(JcaCertStore(listOf(certificate)))
        return generator.generate(InputStreamTypedData(content), false).encoded
    }

    /** long: 测试签名也使用流式 CMS 输入，确保生产代码不会只在小型 byte[] 样例上成立。 */
    private class InputStreamTypedData(private val input: InputStream) : CMSTypedData {
        override fun getContentType() = CMSObjectIdentifiers.data

        override fun write(out: OutputStream) {
            input.copyTo(out)
        }

        override fun getContent(): Any = input
    }

    private fun ByteArray.indexOfSubArray(target: ByteArray): Int {
        if (target.isEmpty() || target.size > size) return -1
        for (start in 0..size - target.size) {
            var matched = true
            for (offset in target.indices) {
                if (this[start + offset] != target[offset]) {
                    matched = false
                    break
                }
            }
            if (matched) return start
        }
        return -1
    }
}
