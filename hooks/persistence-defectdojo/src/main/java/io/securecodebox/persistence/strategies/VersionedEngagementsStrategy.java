/*
 *  secureCodeBox (SCB)
 *  Copyright 2015-2021 iteratec GmbH
 *  https://www.iteratec.com
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  	http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package io.securecodebox.persistence.strategies;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.securecodebox.persistence.defectdojo.ScanType;
import io.securecodebox.persistence.defectdojo.config.DefectDojoConfig;
import io.securecodebox.persistence.defectdojo.models.*;
import io.securecodebox.persistence.defectdojo.service.*;
import io.securecodebox.persistence.exceptions.DefectDojoPersistenceException;
import io.securecodebox.persistence.models.Scan;
import io.securecodebox.persistence.util.DescriptionGenerator;
import io.securecodebox.persistence.util.ScanNameMapping;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;

import java.net.URISyntaxException;
import java.util.List;
import java.util.Objects;

/**
 * VersionedEngagementsStrategy creates a new Engagement for every new version of the software.
 * If a engagement already exists for this version it'll reuse the engagement and append new tests for every scan until the version gets bumped.
 */
public class VersionedEngagementsStrategy implements Strategy {
  private static final Logger LOG = LoggerFactory.getLogger(VersionedEngagementsStrategy.class);

  private final DescriptionGenerator descriptionGenerator = new DescriptionGenerator();

  ProductService productService;
  ProductTypeService productTypeService;
  UserService userService;
  ToolTypeService toolTypeService;
  ToolConfigService toolConfigService;
  EngagementService engagementService;
  TestService testService;
  TestTypeService testTypeService;
  ImportScanService importScanService;

  DefectDojoConfig config;

  public VersionedEngagementsStrategy() {}

  public void init(DefectDojoConfig defectDojoConfig) {
    this.productService = new ProductService(defectDojoConfig);
    this.productTypeService = new ProductTypeService(defectDojoConfig);
    this.userService = new UserService(defectDojoConfig);
    this.toolTypeService = new ToolTypeService(defectDojoConfig);
    this.toolConfigService = new ToolConfigService(defectDojoConfig);
    this.engagementService = new EngagementService(defectDojoConfig);
    this.testService = new TestService(defectDojoConfig);
    this.testTypeService = new TestTypeService(defectDojoConfig);
    this.importScanService = new ImportScanService(defectDojoConfig);

    this.config = defectDojoConfig;
  }

  public void run(Scan scan) throws Exception {
    LOG.debug("Getting DefectDojo User Id");
    var userId = userService.searchUnique(User.builder().username(this.config.getUsername()).build())
      .orElseThrow(() -> new DefectDojoPersistenceException("Failed to find user with name: '" + this.config.getUsername() + "'"))
      .getId();

    LOG.info("Running with DefectDojo User Id: {}", userId);

    long productTypeId = this.ensureProductTypeExistsForScan(scan);
    long productId = this.ensureProductExistsForScan(scan, productTypeId).getId();

    LOG.debug("Looking for existing or creating new DefectDojo Engagement");
    long engagementId = this.createEngagement(scan, productId, userId).getId();
    LOG.debug("Using Engagement with Id: {}", engagementId);

    LOG.debug("Downloading Scan Report (RawResults)");
    String result;
    try {
      result = scan.getRawResults();
    } catch (HttpClientErrorException e) {
      throw new DefectDojoPersistenceException("Failed to download Raw Findings", e);
    }
    LOG.debug("Finished Downloading Scan Report (RawResults)");

    var testId = this.createTest(scan, engagementId, userId);

    LOG.debug("Uploading Scan Report (RawResults) to DefectDojo");

    ScanType scanType = ScanNameMapping.bySecureCodeBoxScanType(scan.getSpec().getScanType()).scanType;
    TestType testType = testTypeService.searchUnique(TestType.builder().name(scanType.getTestType()).build()).orElseThrow(() -> new DefectDojoPersistenceException("Could not find test type '" + scanType.getTestType() + "' in DefectDojo API. DefectDojo might be running in an unsupported version."));

    importScanService.reimportScan(
      result,
      testId,
      userId,
      this.descriptionGenerator.currentDate(),
      scanType,
      testType.getId()
    );

    LOG.info("Uploaded Scan Report (RawResults) as testID {} to DefectDojo", testId);
    LOG.info("All done!");
  }

  /**
   * Creates a new DefectDojo engagement for the given securityTest.
   *
   * @param scan The Scan to crete an DefectDojo engagement for.
   * @return The newly created engagement.
   */
  public Engagement createEngagement(Scan scan, Long productId, Long userId) throws URISyntaxException, JsonProcessingException {
    String engagementName = this.getEngagementsName(scan);

    final String SECURITY_TEST_SERVER_NAME = "secureCodeBox";
    final String SECURITY_TEST_SERVER_DESCRIPTION = "secureCodeBox is a kubernetes based, modularized toolchain for continuous security scans of your software project.";

    var securityTestOrchestrationEngine = toolTypeService.searchUnique(ToolType.builder().name(ToolTypeService.SECURITY_TEST_SERVER_NAME).build()).orElseGet(
      () -> toolTypeService.create(
        ToolType.builder()
          .name(ToolTypeService.SECURITY_TEST_SERVER_NAME)
          .description("Security Test Orchestration Engine")
          .build()
      )
    );

    var toolConfig = toolConfigService.searchUnique(
      ToolConfig.builder().name(SECURITY_TEST_SERVER_NAME).url("https://github.com/secureCodeBox").build()
    ).orElseGet(() -> {
      LOG.info("Creating secureCodeBox Tool Config");
      return toolConfigService.create(
        ToolConfig.builder()
          .toolType(securityTestOrchestrationEngine.getId())
          .name(SECURITY_TEST_SERVER_NAME)
          .description(SECURITY_TEST_SERVER_DESCRIPTION)
          .url("https://github.com/secureCodeBox")
          .configUrl("https://github.com/secureCodeBox")
          .build()
      );
    });


    List<String> tags = scan.getEngagementTags().orElseGet(List::of);
    String version = scan.getEngagementVersion().orElse("");

    var engagement = Engagement.builder()
      .product(productId)
      .name(engagementName)
      .lead(userId)
      .description("")
      .tags(tags)
      .version(version)
      .branch(version)
      .orchestrationEngine(toolConfig.getId())
      .targetStart(descriptionGenerator.currentDate())
      .targetEnd(descriptionGenerator.currentDate())
      .status(Engagement.Status.IN_PROGRESS)
      .build();

    return engagementService.searchUnique(Engagement.builder().product(productId).name(engagementName).version(version).build()).orElseGet(() -> {
      LOG.info("Creating new Engagement as no matching Engagements could be found.");
      return engagementService.create(engagement);
    });
  }

  /**
   * Creates a new productType in DefectDojo if none exists already for the given scan. 
   * If no productType is defined for the given scan a default productType will be used (productType Id = 1).
   * 
   * @param scan The scan to ensure the DefectDojo productType for.
   * @return The productType Id already existing or newly created.
   * @throws URISyntaxException
   * @throws JsonProcessingException
   */
  private long ensureProductTypeExistsForScan(Scan scan) throws URISyntaxException, JsonProcessingException {
    var productTypeNameOptional = scan.getProductType();

    if (productTypeNameOptional.isEmpty()) {
      LOG.info("Using default ProductType as no '{}' annotation was found on the scan", Scan.SecureCodeBoxScanAnnotations.PRODUCT_TYPE.getLabel());
      return 1;
    }

    var productTypeName = productTypeNameOptional.get();

    LOG.info("Looking for ID of ProductType '{}'", productTypeName);

    var productType = productTypeService.searchUnique(ProductType.builder().name(productTypeName).build()).orElseGet(() -> {
      LOG.info("ProductType '{}' didn't already exists creating now", productTypeName);
      return productTypeService.create(ProductType.builder().name(productTypeName).build());
    });

    LOG.info("Using ProductType Id: {}", productType.getId());

    return productType.getId();
  }

  /**
   * Creates a new product in DefectDojo if none exists already related to the given scan and productType.
   * @param scan The scan to ensure the DefectDojo product for.
   * @param productTypeId The id of the productType.
   * @return The existing or newly created product releated to the given scan.
   * @throws URISyntaxException
   * @throws JsonProcessingException
   */
  private Product ensureProductExistsForScan(Scan scan, long productTypeId) throws URISyntaxException, JsonProcessingException {
    String productName = this.getProductName(scan);
    String productDescription = scan.getProductDescription().orElse("Product was automatically created by the secureCodeBox DefectDojo integration");
    List<String> tags = scan.getProductTags().orElseGet(List::of);

    return productService.searchUnique(Product.builder().name(productName).productType(productTypeId).build()).orElseGet(() -> {
      LOG.info("Creating Product: '{}'", productName);
      return productService.create(Product.builder()
        .name(productName)
        .description(productDescription)
        .productType(productTypeId)
        .tags(tags)
        .build()
      );
    });
  }

  /**
   * Creates a new test in DefectDojo related to the given scan and engagement.
   * 
   * @param scan The scan to create a new test in defectDojo for (related to the given engagement).
   * @param engagementId The engagement (referenced by id) to relate the new test to.
   * @param userId The user id corresponding to create the test on behalf to.
   * @return The newly created test id.
   * @throws URISyntaxException
   * @throws JsonProcessingException
   */
  private long createTest(Scan scan, long engagementId, long userId) throws URISyntaxException, JsonProcessingException {
    var startDate = Objects.requireNonNull(scan.getMetadata().getCreationTimestamp()).toString("yyyy-MM-dd HH:mm:ssZ");

    String endDate;
    if (scan.getStatus().getFinishedAt() != null) {
      endDate = scan.getStatus().getFinishedAt().toString("yyyy-MM-dd HH:mm:ssZ");
    } else {
      endDate = DateTime.now().toString("yyyy-MM-dd HH:mm:ssZ");
    }

    String version = scan.getEngagementVersion().orElse(null);

    String scanType = ScanNameMapping.bySecureCodeBoxScanType(scan.getSpec().getScanType()).scanType.getTestType();
    TestType testType = testTypeService.searchUnique(TestType.builder().name(scanType).build()).orElseThrow(() -> new DefectDojoPersistenceException("Could not find test type '" + scanType + "' in DefectDojo API. DefectDojo might be running in an unsupported version."));

    var test = Test.builder()
      .title(scan.getMetadata().getName())
      .description(descriptionGenerator.generate(scan))
      .testType(testType.getId())
      .targetStart(startDate)
      .targetEnd(endDate)
      .engagement(engagementId)
      .lead(userId)
      .percentComplete(100L)
      .version(version)
      .build();

    return testService.create(test).getId();
  }

  /**
   * Returns the DefectDojo Product Name related to the given scan.
   * @param scan The scan the productName relates to.
   * @return The productName related to the given scan.
   */
  protected String getProductName(Scan scan) {
    if (scan.getProductName().isPresent()) {
      return scan.getProductName().get();
    }

    // If the Scan was created via a scheduled scan, the Name of the ScheduledScan should be preferred to the scans name
    if (scan.getMetadata().getOwnerReferences() != null) {
      for (var ownerReference : scan.getMetadata().getOwnerReferences()) {
        if ("ScheduledScan".equals(ownerReference.getKind())) {
          return ownerReference.getName();
        }
      }
    }

    return scan.getMetadata().getName();
  }

  /**
   * Returns the DefectDojo Engagement Name related to the given scan.
   * @param scan The scan the Engagement Name relates to.
   * @return the DefectDojo Engagement Name related to the given scan.
   */
  protected String getEngagementsName(Scan scan) {
    return scan.getEngagementName().orElseGet(() -> scan.getMetadata().getName());
  }
}
