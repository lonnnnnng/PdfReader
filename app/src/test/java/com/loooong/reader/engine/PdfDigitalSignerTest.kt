package com.loooong.reader.engine

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.SecurityProvider
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Provider
import java.security.cert.X509Certificate
import java.util.Date

class PdfDigitalSignerTest {

    @Test
    fun signsPdfWithPkcs12AndValidatesGeneratedSignature() {
        val provider = SecurityProvider.getProvider()
        val keyPair = KeyPairGenerator.getInstance("RSA", provider).apply { initialize(2048) }.generateKeyPair()
        val certificate = createCertificate(keyPair, provider)
        val pkcs12 = createPkcs12(keyPair, certificate, provider, "certificate-password")
        val source = createUnsignedPdf()
        val output = File.createTempFile("digitally-signed-", ".pdf")
        val signingPassword = "certificate-password".toCharArray()
        try {
            val info = PdfDigitalSigner.inspectPkcs12(pkcs12, "certificate-password")
            assertEquals(1, info.entries.size)
            assertEquals("reader-signing", info.entries.single().alias)
            assertEquals(PdfCertificateStatus.VALID, info.entries.single().statusNow)

            val result = PdfDigitalSigner.sign(
                sourceFile = source,
                outputFile = output,
                documentPassword = null,
                pkcs12Bytes = pkcs12,
                pkcs12Password = signingPassword,
                request = PdfDigitalSignRequest(
                    alias = "reader-signing",
                    pageIndex = 0,
                    reason = "批准发布",
                    location = "Shanghai",
                    contactInfo = "reader@example.com",
                    placement = PdfSignaturePlacement.INVISIBLE,
                ),
            )
            assertEquals("Reader Signing Test", result.signerName)
            assertEquals(1, result.signatureCount)
            assertTrue(result.outputBytes > source.length())

            val signature = PdfDigitalSignatureInspector.inspect(output).signatures.single()
            assertEquals(PdfDigitalSignatureStatus.VALID, signature.status)
            assertEquals(PdfSignatureCoverage.WHOLE_DOCUMENT, signature.coverage)
            assertEquals("批准发布", signature.reason)
            assertEquals("Shanghai", signature.location)
        } finally {
            source.delete()
            output.delete()
            pkcs12.fill(0)
            signingPassword.fill('\u0000')
        }
    }

    @Test
    fun reportsWrongPkcs12PasswordClearly() {
        val provider = SecurityProvider.getProvider()
        val keyPair = KeyPairGenerator.getInstance("RSA", provider).apply { initialize(1024) }.generateKeyPair()
        val certificate = createCertificate(keyPair, provider)
        val pkcs12 = createPkcs12(keyPair, certificate, provider, "correct-password")
        try {
            PdfDigitalSigner.inspectPkcs12(pkcs12, "wrong-password")
            fail("错误证书密码不应通过")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("证书密码错误"))
        } finally {
            pkcs12.fill(0)
        }
    }

    @Test
    fun rejectsSigningWhenCurrentPdfPermissionDisallowsModification() {
        val provider = SecurityProvider.getProvider()
        val keyPair = KeyPairGenerator.getInstance("RSA", provider).apply { initialize(1024) }.generateKeyPair()
        val certificate = createCertificate(keyPair, provider)
        val pkcs12 = createPkcs12(keyPair, certificate, provider, "certificate-password")
        val source = File.createTempFile("restricted-", ".pdf")
        val output = File.createTempFile("restricted-signed-", ".pdf")
        val documentPassword = "user-password"
        val signingPassword = "certificate-password".toCharArray()
        try {
            PDDocument().use { document ->
                document.addPage(PDPage())
                val permissions = AccessPermission().apply {
                    setCanModify(false)
                    setCanModifyAnnotations(false)
                    setCanFillInForm(false)
                }
                document.protect(StandardProtectionPolicy("owner-password", documentPassword, permissions))
                source.outputStream().buffered().use(document::save)
            }

            try {
                PdfDigitalSigner.sign(
                    sourceFile = source,
                    outputFile = output,
                    documentPassword = documentPassword,
                    pkcs12Bytes = pkcs12,
                    pkcs12Password = signingPassword,
                    request = PdfDigitalSignRequest(
                        alias = "reader-signing",
                        pageIndex = 0,
                        reason = "权限测试",
                        location = "Shanghai",
                        contactInfo = "reader@example.com",
                        placement = PdfSignaturePlacement.INVISIBLE,
                    ),
                )
                fail("没有修改权限时不应允许添加数字签名")
            } catch (expected: IllegalArgumentException) {
                assertEquals("当前 PDF 权限不允许添加数字签名", expected.message)
            }
        } finally {
            source.delete()
            output.delete()
            pkcs12.fill(0)
            signingPassword.fill('\u0000')
        }
    }

    private fun createUnsignedPdf(): File = File.createTempFile("unsigned-", ".pdf").apply {
        PDDocument().use { document ->
            document.addPage(PDPage())
            outputStream().buffered().use(document::save)
        }
    }

    private fun createPkcs12(
        keyPair: KeyPair,
        certificate: X509Certificate,
        provider: Provider,
        password: String,
    ): ByteArray {
        val pin = password.toCharArray()
        return try {
            val keyStore = KeyStore.getInstance("PKCS12", provider).apply { load(null, pin) }
            keyStore.setKeyEntry("reader-signing", keyPair.private, pin, arrayOf(certificate))
            ByteArrayOutputStream().also { keyStore.store(it, pin) }.toByteArray()
        } finally {
            pin.fill('\u0000')
        }
    }

    private fun createCertificate(keyPair: KeyPair, provider: Provider): X509Certificate {
        val now = System.currentTimeMillis()
        val subject = X500Name("CN=Reader Signing Test,O=Reader")
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
}
