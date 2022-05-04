package org.doubleopen.convert

import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.shouldBe
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.licenses.LicenseView
import org.ossreviewtoolkit.model.utils.createLicenseInfoResolver
import org.ossreviewtoolkit.utils.spdx.SpdxExpression
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

    "SPDX example" should {
        "be deserializable" {
            val spdxFile = File("src/test/resources/test-data.spdx.json").absoluteFile
            SpdxModelMapper.read<SpdxDocument>(spdxFile)
        }

        "be convertable to OrtResult" {
            val spdxFile = File("src/test/resources/test-data.spdx.json").absoluteFile
            val spdx = SpdxModelMapper.read<SpdxDocument>(spdxFile)
            spdxToOrt(spdx, false)
        }

        "have correct amount of packages" {
            val spdxFile = File("src/test/resources/test-data.spdx.json").absoluteFile
            val spdx = SpdxModelMapper.read<SpdxDocument>(spdxFile)
            val ortResult = spdxToOrt(spdx, false)

            ortResult.analyzer!!.result.packages.size shouldBe 2
        }
    }

    "Package" should {
        "have correct declared license" {
            val spdxFile = File("src/test/resources/test-data.spdx.json").absoluteFile
            val spdx = SpdxModelMapper.read<SpdxDocument>(spdxFile)
            val ortResult = spdxToOrt(spdx, false)

            val package1 = ortResult.getPackage(Identifier("Yocto::package1-lib:1.0"))!!

            package1.pkg.declaredLicensesProcessed.spdxExpression!! shouldBe SpdxExpression.Companion.parse("GPL-2.0-only")
        }

        "have correct licenses from files" {
            val spdxFile = File("src/test/resources/test-data.spdx.json").absoluteFile
            val spdx = SpdxModelMapper.read<SpdxDocument>(spdxFile)
            val ortResult = spdxToOrt(spdx, false)

            val licenseInfoResolver = ortResult.createLicenseInfoResolver()
            val resolvedLicenses = licenseInfoResolver.resolveLicenseInfo(Identifier("Yocto::package1-lib:1.0"))
            resolvedLicenses.effectiveLicense(LicenseView.CONCLUDED_OR_DECLARED_AND_DETECTED, emptyList())
                .toString() shouldBe "GPL-2.0-only AND Apache-2.0 AND GPL-2.0 AND GPL-2.0-or-later"
        }

        "have correct licenses from source files from other packages" {
            val spdxFile = File("src/test/resources/test-data.spdx.json").absoluteFile
            val spdx = SpdxModelMapper.read<SpdxDocument>(spdxFile)
            val ortResult = spdxToOrt(spdx, false)

            val licenseInfoResolver = ortResult.createLicenseInfoResolver()
            val resolvedLicenses = licenseInfoResolver.resolveLicenseInfo(Identifier("Yocto::package2-lib:1.0"))
            resolvedLicenses.effectiveLicense(LicenseView.CONCLUDED_OR_DECLARED_AND_DETECTED, emptyList())
                .toString() shouldBe "MIT AND AGPL-3.0 AND LGPL-2.0"
        }
    }
})