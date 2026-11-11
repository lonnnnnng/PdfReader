package com.loooong.pdfreader

import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PdfReaderFontSubsetter
import com.tom_roush.pdfbox.pdmodel.encryption.*
import java.io.OutputStream
import java.util.IdentityHashMap

/** long: PDFBox 加密写盘会原地改写字符串与流，因此只序列化独立对象图，当前会话始终保留明文对象。 */
internal object PdfSerialization {
    fun write(source: PDDocument, output: OutputStream) {
        PdfReaderFontSubsetter.finish(source)
        PDDocument().use { target ->
            val copies = IdentityHashMap<COSBase, COSBase>()
            fun copy(value: COSBase?): COSBase? {
                if (value == null) return null
                copies[value]?.let { return it }
                return when (value) {
                    is COSObject -> COSObject(null).also { reference ->
                        // long: 共享字符串和数组必须保留间接引用，否则 writer 会在多个字段重复加密同一对象。
                        copies[value] = reference
                        reference.`object` = copy(value.`object`)
                    }
                    is COSString -> COSString(value.bytes.clone()).also {
                        it.forceHexForm = value.forceHexForm
                        copies[value] = it
                    }
                    is COSArray -> COSArray().also { array ->
                        copies[value] = array
                        value.forEach { array.add(copy(it)) }
                    }
                    is COSDictionary -> {
                        val dictionary = if (value is COSStream) {
                            target.document.createCOSStream().also { stream ->
                                value.createRawInputStream().use { input ->
                                    stream.createRawOutputStream().use { input.copyTo(it) }
                                }
                            }
                        } else COSDictionary()
                        copies[value] = dictionary
                        value.entrySet().forEach { (key, item) -> dictionary.setItem(key, copy(item)) }
                        dictionary
                    }
                    else -> value // long: 名称、数字和布尔值不可变，可以安全共用。
                }
            }
            target.document.trailer = copy(source.document.trailer) as COSDictionary
            target.version = source.version
            target.isAllSecurityToBeRemoved = source.isAllSecurityToBeRemoved
            if (source.encryption != null && !source.isAllSecurityToBeRemoved) {
                val encryption = PDEncryption(copy(source.encryption.cosObject) as COSDictionary)
                val handler = source.encryption.securityHandler
                encryption.securityHandler = if (handler.hasProtectionPolicy()) handler
                    else PreservedEncryption(handler, encryption)
                target.setEncryptionDictionary(encryption)
            }
            // long: 完整写入只包含仍可达的对象，永久遮盖和压缩不能携带增量历史中的旧内容。
            target.save(output)
        }
    }

    /** long: 已经验证密码的会话沿用原密钥、权限和 ID，不需要获知或重置所有者密码。 */
    private class PreservedEncryption(
        private val original: SecurityHandler,
        private val encryption: PDEncryption,
    ) : SecurityHandler() {
        override fun hasProtectionPolicy() = true
        override fun prepareDocumentForEncryption(document: PDDocument) = Unit
        override fun prepareForDecryption(encryption: PDEncryption, id: COSArray, material: DecryptionMaterial) =
            throw UnsupportedOperationException("该适配器仅用于已解密文档的副本保存")

        override fun encryptStream(stream: COSStream, objectNumber: Long, generationNumber: Int) {
            if (!original.isDecryptMetadata && stream.getCOSName(COSName.TYPE) == COSName.METADATA) return
            if (encryption.version >= 4 && encryption.streamFilterName == COSName.IDENTITY) return
            original.encryptStream(stream, objectNumber, generationNumber)
        }

        override fun encryptString(string: COSString, objectNumber: Long, generationNumber: Int) {
            if (encryption.version >= 4 && encryption.stringFilterName == COSName.IDENTITY) return
            original.encryptString(string, objectNumber, generationNumber)
        }
    }
}
