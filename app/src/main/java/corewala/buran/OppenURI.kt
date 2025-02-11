package corewala.buran

import corewala.toURI

const val GEMSCHEME = "gemini://"
const val TRAVERSE = "../"
const val SOLIDUS = "/"
const val DIREND = "/"
const val QUERY = "?"

/**
 *
 * Easy uri path handling for Gemini
 *
 */
class OppenURI constructor(private var ouri: String) {

    constructor(): this("")

    var host: String = ""
    var scheme: String = ""

    init {
        if(ouri.isNotEmpty()){
            host = ouri.toURI().host
            scheme = ouri.toURI().scheme
        }
    }

    fun set(ouri: String){
        this.ouri = ouri
        if(ouri.isNotEmpty()){
            host = ouri.toURI().host
            scheme = ouri.toURI().scheme
        }
    }

    fun resolve(reference: String): String{
        if(ouri == "$GEMSCHEME$host") ouri = "$ouri/"
        when {
            reference.startsWith(GEMSCHEME) -> set(reference)
            reference.startsWith(SOLIDUS) -> ouri = "$scheme://$host$reference"
            reference.startsWith(TRAVERSE) -> {
                if(!ouri.endsWith(DIREND)) ouri = ouri.removeFile()
                val traversalCount = reference.split(TRAVERSE).size - 1
                ouri = traverse(traversalCount) + reference.replace(TRAVERSE, "")
            }
            reference.startsWith(QUERY) -> {
                ouri = if(reference.contains(QUERY)){
                    ouri.substringBefore(QUERY) + reference
                }else{
                    ouri + reference
                }
            }
            else -> {
                ouri = when {
                    ouri.endsWith(DIREND) -> {
                        "${ouri}$reference"
                    }
                    else -> "${ouri.substring(0, ouri.lastIndexOf("/"))}/$reference"
                }
            }
        }
        return ouri
    }

    private fun traverse(count: Int): String{
        val path = ouri.removePrefix("$GEMSCHEME$host")
        val segments  = path.split(SOLIDUS).filter { it.isNotEmpty() }
        val segmentCount = segments.size
        var nouri = "$GEMSCHEME$host"

        segments.forEachIndexed{ index, segment ->
            if(index < segmentCount - count){
                nouri += "/$segment"
            }
        }

        return "$nouri/"

    }

    fun copy(): OppenURI = OppenURI(ouri)

    override fun toString(): String = ouri

    private fun String.removeFile(): String{
        return this.substring(0, ouri.lastIndexOf("/") + 1)
    }
}
