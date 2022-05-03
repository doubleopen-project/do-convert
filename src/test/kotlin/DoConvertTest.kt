package org.doubleopen.convert

import io.kotest.core.spec.style.WordSpec
import org.ossreviewtoolkit.utils.spdx.SpdxModelMapper
import org.ossreviewtoolkit.utils.spdx.model.SpdxDocument
import java.io.File


class DoConvertTest : WordSpec({
    "SPDX from meta-doubleopen" should {
        "be deserializable" {
            val spdxFile = File("src/test/resources/core-image-minimal.spdx.json").absoluteFile
            SpdxModelMapper.read<SpdxDocument>(spdxFile)
        }

        "be convertable to OrtResult" {
            val spdxFile = File("src/test/resources/core-image-minimal.spdx.json").absoluteFile
            val spdx = SpdxModelMapper.read<SpdxDocument>(spdxFile)
            spdxToOrt(spdx, false)
        }
    }
})