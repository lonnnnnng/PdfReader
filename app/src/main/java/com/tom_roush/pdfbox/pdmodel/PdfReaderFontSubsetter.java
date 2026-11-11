package com.tom_roush.pdfbox.pdmodel;

import com.tom_roush.pdfbox.pdmodel.font.PDFont;
import java.io.IOException;
import java.util.Iterator;

/** long: PDFBox 2.0.27.0 只在完整 save 内处理这个包级集合；复制文档前必须恰好完成一次子集。 */
public final class PdfReaderFontSubsetter {
    private PdfReaderFontSubsetter() { }

    /** long: 手工生成 Tf/Tj 绕过了 setFont，必须同时把字体加入文档的待子集集合。 */
    public static void register(PDDocument document, PDFont font) {
        if (font.willBeSubset()) document.getFontsToSubset().add(font);
    }

    public static void finish(PDDocument document) throws IOException {
        Iterator<PDFont> iterator = document.getFontsToSubset().iterator();
        while (iterator.hasNext()) {
            iterator.next().subset();
            // long: 已完成的子集不能再次关闭并裁剪字体；后续字体失败也不应重复处理前面的成功项。
            iterator.remove();
        }
    }
}
