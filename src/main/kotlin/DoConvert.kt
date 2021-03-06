/*
 * Copyright (C) 2021 HH Partners
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package org.doubleopen.convert

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import org.ossreviewtoolkit.model.*
import org.ossreviewtoolkit.model.config.AnalyzerConfiguration
import org.ossreviewtoolkit.model.config.ScannerConfiguration
import org.ossreviewtoolkit.utils.common.expandTilde
import org.ossreviewtoolkit.utils.core.Environment
import org.ossreviewtoolkit.utils.spdx.SpdxConstants
import org.ossreviewtoolkit.utils.spdx.SpdxModelMapper.read
import org.ossreviewtoolkit.utils.spdx.model.SpdxDocument
import org.ossreviewtoolkit.utils.spdx.model.SpdxFile
import org.ossreviewtoolkit.utils.spdx.model.SpdxPackage
import org.ossreviewtoolkit.utils.spdx.model.SpdxRelationship
import org.ossreviewtoolkit.utils.spdx.toSpdx
import java.time.Instant

fun main(args: Array<String>) {
    return DoConvert().main(args)
}

internal class DoConvert : CliktCommand(
    help = "Convert SPDX Document to ORT result file."
) {
    private val spdxFile by option(
        "--spdx", "-i",
        help = "SPDX Document to convert to ORT file."
    ).convert { it.expandTilde() }
        .file(mustExist = true, canBeFile = true, canBeDir = false, mustBeWritable = false, mustBeReadable = false)
        .required()

    private val ortResultFile by option(
        "--ort-result-file", "-o",
        help = "The output ORT file."
    ).convert { it.expandTilde() }
        .file(mustExist = false, canBeFile = true, canBeDir = false, mustBeWritable = false, mustBeReadable = false)
        .required()

    private val repositoryConfigurationFile by option(
        "--repository-configuration-file",
        help = "Repository configuration to add."
    ).convert { it.expandTilde() }
        .file(mustExist = true, canBeFile = true, canBeDir = false, mustBeWritable = false, mustBeReadable = true)

    private val skipScanResultsConversion: Boolean by option("--skip-scan").flag()

    override fun run() {
        val spdxDocument = read<SpdxDocument>(spdxFile)

        var ortResult = spdxToOrt(spdxDocument, skipScanResultsConversion)

        repositoryConfigurationFile?.let {
            ortResult = ortResult.replaceConfig(it.readValue())
        }

        ortResultFile.mapper().writerWithDefaultPrettyPrinter().writeValue(ortResultFile, ortResult)
    }
}

/**
 * Convert SPDX Document from Yocto created by meta-doubleopen layer to an ORT Result file.
 */
fun spdxToOrt(spdxDocument: SpdxDocument, skipScanResultsConversion: Boolean): OrtResult {
    /* Store image from the SPDX as a project in the ORT Result. SPDX's documentDescribes should always include the root
     * package, so not-null assertion should be alright.
     */
    val projects = sortedSetOf<Project>()
    val imagePackage =
        spdxDocument.packages.find { spdxPackage -> spdxPackage.spdxId in spdxDocument.documentDescribes }!!

    // Scope for packages that are distributed with the image.
    val scopeDistributed = Scope("distributed")

    // Scope for packages that are built but not distributed with the image.
    val scopeNotDistributed = Scope("notDistributed")

    // Scope for Yocto recipes.
    val scopeRecipes = Scope("recipes")

    // Create map for files by SPDX ID to make look-ups faster.
    val filesBySpdxId: HashMap<String, SpdxFile> = spdxDocument.files.associateByTo(HashMap()) { it.spdxId }

    // Create map for relationships by SPDX ID to make look-ups faster.
    val relationshipsBySpdxId: MutableMap<String, MutableList<SpdxRelationship>> =
        mutableMapOf<String, MutableList<SpdxRelationship>>().withDefault { mutableListOf() }
    for (relationship in spdxDocument.relationships) {
        relationshipsBySpdxId.computeIfAbsent(relationship.spdxElementId) { mutableListOf() } += relationship
    }

    // Add packages from SPDX to ORT file.
    val packages = sortedSetOf<CuratedPackage>()
    val scanResults = sortedMapOf<Identifier, List<ScanResult>>()
    for (spdxPackage in spdxDocument.packages) {
        if (spdxPackage.spdxId !in spdxDocument.documentDescribes) {

            // Add package to the correct scope.
            if (spdxDocument.relationships.any { spdxRelationship ->
                    (spdxRelationship.spdxElementId == spdxPackage.spdxId) &&
                            (spdxRelationship.relationshipType == SpdxRelationship.Type.PACKAGE_OF)
                }) {
                val ortPackage = spdxPackageToOrtPackage(spdxPackage).toCuratedPackage()
                packages.add(ortPackage)
                scopeDistributed.dependencies.add(ortPackage.pkg.toReference())

                // Get files contained by the package.
                val packageContainsRelationships =
                    relationshipsBySpdxId.getValue(spdxPackage.spdxId)
                        .filter { it.relationshipType == SpdxRelationship.Type.CONTAINS }

                val containedFiles = mutableListOf<SpdxFile>()
                for (spdxRelationship in packageContainsRelationships) {
                    val containedFile = filesBySpdxId[spdxRelationship.relatedSpdxElement]!!
                    containedFiles.add(containedFile)
                    val generatingRelationships =
                        relationshipsBySpdxId.getValue(containedFile.spdxId)
                            .filter { it.relationshipType == SpdxRelationship.Type.GENERATED_FROM }

                    for (generatingRelationship in generatingRelationships) {
                        containedFiles.add(filesBySpdxId[generatingRelationship.relatedSpdxElement]!!)
                    }
                }

                // Add license and copyright findings for the contained files.
                val copyrightFindings = sortedSetOf<CopyrightFinding>()
                val licenseFindings = sortedSetOf<LicenseFinding>()
                for (containedFile in containedFiles) {
                    /* If concludedLicense in SPDX is NOASSERTION, add the scanner hits from licenseInfoInFiles to the ORT
                     * file. If concludedLicense is something else, add it.
                     */
                    val fileFindings = fileFindingsFromSpdxFile(containedFile, skipScanResultsConversion)

                    // Add license findings if not NONE.
                    fileFindings.licenseFinding?.let { licenseFindings.add(fileFindings.licenseFinding) }

                    // Add copyrights if it's not NOASSERTION.
                    fileFindings.copyrightFinding?.let { copyrightFindings.add(it) }
                }

                val scanSummary =
                    ScanSummary(Instant.EPOCH, Instant.EPOCH, "", licenseFindings, copyrightFindings)
                val scanResult = ScanResult(UnknownProvenance, ScannerDetails.EMPTY, scanSummary)
                scanResults[ortPackage.pkg.id] = listOf(scanResult)
            }
        }
    }

    val scopes = sortedSetOf<Scope>()
    scopes.add(scopeDistributed)
    scopes.add(scopeNotDistributed)
    scopes.add(scopeRecipes)

    val project = Project(
        id = Identifier("Yocto", "", imagePackage.name, imagePackage.versionInfo),
        declaredLicenses = sortedSetOf<String>(),
        definitionFilePath = "",
        homepageUrl = imagePackage.homepage,
        vcs = VcsInfo.EMPTY,
        scopeDependencies = scopes
    )

    projects.add(project)

    val analyzerResult = AnalyzerResult(projects, packages)
    val analyzerRun =
        AnalyzerRun(result = analyzerResult, config = AnalyzerConfiguration(), environment = Environment())

    val scanRecord = ScanRecord(scanResults = scanResults, storageStats = AccessStatistics())
    val scannerRun = ScannerRun(results = scanRecord, config = ScannerConfiguration(), environment = Environment())
    return OrtResult(Repository.EMPTY, analyzer = analyzerRun, scanner = scannerRun)
}

/**
 * Convert SPDX Package from Yocto to ORT Package.
 */
private fun spdxPackageToOrtPackage(spdxPackage: SpdxPackage): Package {
    // Name of recipe may be the same as one of the packages created by the recipe. Append "-recipe" to the name part of
    // the name for recipes.
    val id = if (spdxPackage.spdxId.startsWith("SPDXRef-Recipe")) {
        Identifier("Yocto", "", spdxPackage.name + "-recipe", spdxPackage.versionInfo)
    } else {
        Identifier("Yocto", "", spdxPackage.name, spdxPackage.versionInfo)
    }
    val declaredLicenses = spdxPackage.licenseDeclared.toSpdx().licenses()

    return Package(
        id = id,
        declaredLicenses = declaredLicenses.toSortedSet(),
        binaryArtifact = RemoteArtifact.EMPTY,
        description = spdxPackage.description,
        homepageUrl = spdxPackage.homepage,
        sourceArtifact = RemoteArtifact.EMPTY,
        vcs = VcsInfo.EMPTY
    )
}

/**
 * Data class including nullable [LicenseFinding] and nullable [CopyrightFinding].
 */
private data class FileFindings(val licenseFinding: LicenseFinding?, val copyrightFinding: CopyrightFinding?)

/**
 * Convert an [SpdxFile] to [FileFindings].
 */
private fun fileFindingsFromSpdxFile(spdxFile: SpdxFile, skipScanResultsConversion: Boolean): FileFindings {
    val scannerHit = 100000
    val conclusion = 200000
    /* If concludedLicense in SPDX is NOASSERTION, add the scanner hits from licenseInfoInFiles to the ORT
     * file. If concludedLicense is something else, add it.
     */
    val licenseFinding =
        if (spdxFile.licenseConcluded.contains(SpdxConstants.NOASSERTION) && spdxFile.licenseInfoInFiles.isNotEmpty()) {
            // LicenseInfoInFiles is a list of SPDX IDs. Join with AND to create an SPDX expression.
            val licenseInfoInFiles = spdxFile.licenseInfoInFiles.filter { it != SpdxConstants.NOASSERTION }
            if (licenseInfoInFiles.isNotEmpty()) {
                if (skipScanResultsConversion) {
                    LicenseFinding(
                        "(" + licenseInfoInFiles.joinToString(separator = " AND ") + ")",
                        TextLocation(spdxFile.spdxId, scannerHit)
                    )
                } else {
                    LicenseFinding(
                        "(" + licenseInfoInFiles.joinToString(separator = " AND ") {
                            val license = it.removePrefix("LicenseRef-")
                            "LicenseRef-Scanner-$license"
                        } + ")",
                        TextLocation(spdxFile.spdxId, scannerHit)
                    )
                }
            } else {
                null
            }
        } else if (spdxFile.licenseConcluded == SpdxConstants.NONE) {
            null
        } else {
            LicenseFinding(spdxFile.licenseConcluded, TextLocation(spdxFile.spdxId, conclusion))
        }

    // Return copyrights if not NOASSERTION.
    val copyrightFinding = if (spdxFile.copyrightText != SpdxConstants.NOASSERTION) {
        CopyrightFinding(spdxFile.copyrightText, TextLocation(spdxFile.spdxId, -1))
    } else {
        null
    }

    return FileFindings(licenseFinding, copyrightFinding)
}
