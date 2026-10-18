package br.com.bkm.assinapdf.verify

import org.apache.pdfbox.contentstream.PDFStreamEngine
import org.apache.pdfbox.contentstream.operator.DrawObject
import org.apache.pdfbox.contentstream.operator.state.Concatenate
import org.apache.pdfbox.contentstream.operator.state.Restore
import org.apache.pdfbox.contentstream.operator.state.Save
import org.apache.pdfbox.contentstream.operator.state.SetMatrix
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.util.Matrix

/** Percorre o conteúdo da página e registra a matriz (CTM) de cada Form XObject desenhado no nível da página. */
class FormProbe : PDFStreamEngine() {
    val forms = mutableListOf<Matrix>()
    var images = 0
    private var depth = 0

    init {
        addOperator(Concatenate())
        addOperator(Save())
        addOperator(Restore())
        addOperator(SetMatrix())
        addOperator(DrawObject())
    }

    override fun showForm(form: PDFormXObject) {
        if (depth == 0) forms += graphicsState.currentTransformationMatrix.clone()
        depth++
        try { super.showForm(form) } finally { depth-- }
    }

    override fun processOperator(operator: org.apache.pdfbox.contentstream.operator.Operator, operands: MutableList<org.apache.pdfbox.cos.COSBase>) {
        if (operator.name == "Do" && operands.isNotEmpty()) {
            val name = operands[0] as? org.apache.pdfbox.cos.COSName
            val xobj = name?.let { resources?.getXObject(it) }
            if (xobj is PDImageXObject) images++
        }
        super.processOperator(operator, operands)
    }

    companion object {
        fun probe(page: PDPage): FormProbe = FormProbe().also { it.processPage(page) }
    }
}
