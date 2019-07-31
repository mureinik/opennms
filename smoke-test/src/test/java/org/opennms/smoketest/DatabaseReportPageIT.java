/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2019-2019 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2019 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.smoketest;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.apache.logging.log4j.util.Strings;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.opennms.smoketest.ui.framework.Button;
import org.opennms.smoketest.ui.framework.CheckBox;
import org.opennms.smoketest.ui.framework.DeleteAllButton;
import org.opennms.smoketest.ui.framework.Select;
import org.opennms.smoketest.ui.framework.TextInput;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.testcontainers.shaded.com.google.common.collect.Lists;

public class DatabaseReportPageIT extends UiPageTest {

    private DatabaseReportPage page;

    @Before
    public void before() {
        driver.manage().timeouts().implicitlyWait(10, SECONDS);
        page = new DatabaseReportPage(getBaseUrlInternal());
        page.open();
    }

    @After
    public void after() {
        new ScheduledReportsTab().open().deleteAll();
        new PersistedReportsTab().open().deleteAll();
    }

    @Test
    public void verifyAdhocReport() {
        // Run Report
        new ReportTemplateTab().open()
                .select(EarlyMorningReport.name)
                .format(Formats.PDF)
                .createReport();

        // Verify Creation
        final File downloadedFile = new File(getDownloadsFolder(), EarlyMorningReport.filename(Formats.PDF));
        await().atMost(2, MINUTES).pollInterval(5, SECONDS).until(() -> downloadedFile.exists());
    }

    @Test
    public void verifyDeliverReport() {
        // Trigger Delivery
        new ReportTemplateTab().open()
                .select(EarlyMorningReport.name)
                .deliverReport(DeliveryOptions.DEFAULTS);

        // Verify delivery
        await().atMost(2, MINUTES).pollInterval(5, SECONDS)
                .until( () -> {
                        new Button(driver, "action.refresh").click();
                        final Optional<PersistedReportElement> any = new PersistedReportsTab()
                            .open()
                            .getPersistedReports().stream()
                            .filter((input) -> input.title.equals(EarlyMorningReport.id + " admin"))
                            .findAny();
                        return any.isPresent();
                    }
                );
    }

    @Test
    public void verifyScheduleReport() {
        // Define Schedule
        final String cronExpression = "0 0 0/5 * * ?";
        new ReportTemplateTab().open()
                .select(EarlyMorningReport.name)
                .scheduleReport(DeliveryOptions.DEFAULTS, cronExpression);

        // Verify Schedule
        final Optional<ReportScheduleElement> any = new ScheduledReportsTab()
                .open()
                .getScheduledReports().stream()
                .filter((input) -> input.templateName.equals(EarlyMorningReport.id) && input.cronExpression.equals(cronExpression))
                .findAny();
        assertThat(any.isPresent(), is(true));
    }

    private void closeDialogue() {
        execute(() -> findElementByXpath("//div[@class='modal-body']//button[text()='Show me']")).click();
    }

    private interface EarlyMorningReport {
        String name = "Early morning report";
        String id = "local_Early-Morning-Report";

        static String filename(String format) {
            return id + "." + format.toLowerCase();
        }
    }

    private interface Formats {
        String PDF = "PDF";
        String CSV = "CSV";
    }

    private class DatabaseReportPage {
        private final String url;

        public DatabaseReportPage(String baseUrl) {
            this.url = Objects.requireNonNull(baseUrl) + "opennms/report/database/index.jsp";
        }

        public DatabaseReportPage open() {
            driver.get(url);
            pageContainsText("Early morning report");
            return this;
        }
    }

    private class ReportTemplateTab {

        public ReportTemplateTab select(String reportName) {
            final WebElement element = findElementByXpath(String.format("//a/h5[text() = '%s']", reportName));
            element.click();
            return this;
        }

        public ReportTemplateTab format(String format) {
            ensureReportIsSelected();
            new Select(driver, "reportFormat").setValueByText(format);
            return this;
        }

        public ReportTemplateTab open() {
            getElement().click();
            assertThat(isActive(), Matchers.is(true));
            return this;
        }

        public WebElement getElement() {
            return execute(() -> findElementByXpath("//a[@data-name='report-templates']"));
        }

        public boolean isActive() {
            return getElement().getAttribute("class").contains("active");
        }

        public ReportTemplateTab createReport() {
            ensureReportIsSelected();
            final WebElement executeButton = execute(() -> findElementById("execute"));
            assertThat(executeButton.getText(), Matchers.is("Create Report"));
            executeButton.click();
            return this;
        }

        public ReportTemplateTab deliverReport(final DeliveryOptions options) {
            ensureReportIsSelected();
            applyDeliveryOptions(options);

            // Finally deliver the report
            final WebElement executeButton = execute(() -> findElementById("execute"));
            assertThat(executeButton.getText(), Matchers.is("Deliver Report"));
            executeButton.click();
            closeDialogue();
            return this;
        }

        public ReportTemplateTab scheduleReport(DeliveryOptions options, String cronExpression) {
            ensureReportIsSelected();
            applyDeliveryOptions(options);

            new CheckBox(driver, "createSchedule").setSelected(true);
            new CheckBox(driver, "scheduleTypeCustom").setSelected(true);
            new TextInput(driver, "customCronExpressionInput").setInput(cronExpression);

            final WebElement executeButton = execute(() -> findElementById("execute"));
            assertThat(executeButton.getText(), Matchers.is("Schedule Report"));
            executeButton.click();
            closeDialogue();
            return this;
        }

        private void ensureReportIsSelected() {
            if (getSelectedTemplate() == null) {
                throw new IllegalStateException("No report has been selected.");
            }
        }

        private void applyDeliveryOptions(DeliveryOptions options) {
            Objects.requireNonNull(options);

            // Enable delivery
            final CheckBox deliverCheckbox = new CheckBox(getDriver(), "deliverReport");
            deliverCheckbox.setSelected(true);

            // Fill values
            // Persist to Disk?
            final CheckBox persistCheckbox = new CheckBox(getDriver(), "persistToggle");
            persistCheckbox.setSelected(options.persistToDisk);

            // Send Mail?
            final CheckBox recipientCheckbox = new CheckBox(getDriver(), "sendMailToggle");
            recipientCheckbox.setSelected(!options.emailRecipients.isEmpty());
            if (!options.emailRecipients.isEmpty()) {
                final String emailRecipients = Strings.join(options.emailRecipients, ',');
                new TextInput(getDriver(), "mailRecipient").setInput(emailRecipients);
            }

            // Format
            new Select(driver, "format").setValueByText(options.format);
        }

        private String getSelectedTemplate() {
            final List<WebElement> elements = getDriver().findElements(By.xpath("//div[contains(@class, 'list-group')]//a[contains(@class, 'list-group-item') and contains(@class, 'active')]/h5"));
            if (!elements.isEmpty()) {
                return elements.get(0).getText();
            }
            return null;
        }
    }

    private class PersistedReportsTab {

        public PersistedReportsTab open() {
            getElement().click();
            assertThat(isActive(), Matchers.is(true));
            return this;
        }

        public WebElement getElement() {
            return execute(() -> findElementByXpath("//a[@data-name='report-persisted']"));
        }

        public boolean isActive() {
            return getElement().getAttribute("class").contains("active");
        }

        public List<PersistedReportElement> getPersistedReports() {
            final List<PersistedReportElement> results = Lists.newArrayList();
            final List<WebElement> rows = execute(() -> driver.findElementsByXPath("//table/tbody/tr"));
            for (WebElement eachRow : rows) {
                final List<WebElement> columns = eachRow.findElements(By.xpath("./td"));
                final PersistedReportElement element = new PersistedReportElement();
                element.setTitle(columns.get(2).getText());
                element.setReportId(columns.get(3).getText());
                element.setRunDate(columns.get(4).getText());
                results.add(element);
            }
            return results;
        }

        public void deleteAll() {
            if (!getPersistedReports().isEmpty()) {
                new DeleteAllButton(driver).click();
                await().atMost(2, MINUTES).pollInterval(5, SECONDS).until(() -> getPersistedReports().isEmpty());
            }
        }
    }

    private class ScheduledReportsTab {

        public ScheduledReportsTab open() {
            getElement().click();
            assertThat(isActive(), Matchers.is(true));
            return this;
        }

        public WebElement getElement() {
            return execute(() -> findElementByXpath("//a[@data-name='report-schedules']"));
        }

        public boolean isActive() {
            return getElement().getAttribute("class").contains("active");
        }

        public List<ReportScheduleElement> getScheduledReports() {
            final List<ReportScheduleElement> results = Lists.newArrayList();
            final List<WebElement> rows = execute(() -> driver.findElementsByXPath("//table/tbody/tr"));
            for (WebElement eachRow : rows) {
                final List<WebElement> columns = eachRow.findElements(By.xpath("./td"));
                final ReportScheduleElement element = new ReportScheduleElement();
                element.setTemplateName(columns.get(1).getText());
                element.setFormat(columns.get(2).getText());
                element.setCronExpression(columns.get(5).getText());
                element.setTriggerName(columns.get(6).getText());
                results.add(element);
            }
            return results;
        }

        public void deleteAll() {
            if (!getScheduledReports().isEmpty()) {
                new DeleteAllButton(driver).click();
                await().atMost(2, MINUTES).pollInterval(5, SECONDS).until(() -> getScheduledReports().isEmpty());
            }
        }
    }

    private static class DeliveryOptions {

        private static final DeliveryOptions DEFAULTS = new DeliveryOptions()
                .format(Formats.PDF)
                .persistToDisk(true)
                .emailRecipients(Lists.newArrayList());

        private String format;
        private boolean persistToDisk;
        private List<String> emailRecipients;

        private DeliveryOptions format(String format) {
            this.format = Objects.requireNonNull(format);
            return this;
        }

        private DeliveryOptions persistToDisk(boolean persistToDisk) {
            this.persistToDisk = persistToDisk;
            return this;
        }

        private DeliveryOptions emailRecipients(List<String> recipients) {
            this.emailRecipients = new ArrayList<>(recipients);
            return this;
        }
    }

    private static class PersistedReportElement {

        private String reportId;
        private String title;
        private String runDate;

        public void setTitle(String title) {
            this.title = Objects.requireNonNull(title);
        }

        public void setReportId(String reportId) {
            this.reportId = Objects.requireNonNull(reportId);
        }

        public void setRunDate(String runDate) {
            this.runDate = Objects.requireNonNull(runDate);
        }
    }

    private static class ReportScheduleElement {

        private String triggerName;
        private String templateName;
        private String format;
        private String cronExpression;

        public void setTemplateName(String templateName) {
            this.templateName = Objects.requireNonNull(templateName);
        }

        public void setFormat(String format) {
            this.format = Objects.requireNonNull(format);
        }

        public void setCronExpression(String cronExpression) {
            this.cronExpression = Objects.requireNonNull(cronExpression);
        }

        public void setTriggerName(String triggerName) {
            this.triggerName = Objects.requireNonNull(triggerName);
        }
    }

}