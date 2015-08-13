package org.rapla.components.i18n;

import java.io.File;
import java.util.Locale;

import org.junit.Assert;
import org.junit.Test;
import org.rapla.components.i18n.server.locales.I18nLocaleLoadUtil;
import org.rapla.components.util.DateTools;

public class TestI18nLocaleFormats
{
    @Test
    public void testEncoding() throws Exception
    {
        final I18nLocaleFormats formats = I18nLocaleLoadUtil.read(Locale.GERMANY);
        final String month = formats.getMonths()[2];
        Assert.assertEquals("M�rz", month);
    }

    @Test
    public void testAllFilesReadable()
    {
        final File dir = new File(I18nLocaleLoadUtil.class.getResource(I18nLocaleLoadUtil.class.getSimpleName() + ".class").getFile()).getParentFile();
        final File[] listFiles = dir.listFiles();
        final String suffix = ".properties";
        for (File file : listFiles)
        {
            final String name = file.getName();
            if (name.endsWith(suffix))
            {
                final Locale localeString = DateTools.getLocale(name.substring(0, name.length() - suffix.length()));
                try
                {
                    final I18nLocaleFormats format = I18nLocaleLoadUtil.read(localeString);
                    Assert.assertNotNull(format.getFormatDateLong());
                    Assert.assertNotNull(format.isAmPmFormat());
                    Assert.assertNotNull(format.getAmPmFormat());
                    Assert.assertNotNull(format.getFormatDateShort());
                    Assert.assertNotNull(format.getFormatHour());
                    Assert.assertNotNull(format.getFormatMonthYear());
                    Assert.assertNotNull(format.getFormatTime());
                    Assert.assertNotNull(format.getMonths());
                    Assert.assertNotNull(format.getWeekdays());
                }
                catch (Exception e)
                {
                    Assert.fail("failed to load " + name);
                }
            }
        }
    }

    //    @Test
    public void testJreLocales()
    {
        final Locale[] availableLocales = Locale.getAvailableLocales();
        for (Locale locale : availableLocales)
        {
            final String localeString = locale.toString();
            if (!localeString.trim().isEmpty())
            {
                try
                {
                    final I18nLocaleFormats format = I18nLocaleLoadUtil.read(locale);
                    Assert.assertNotNull(format.getFormatDateLong());
                    Assert.assertNotNull(format.isAmPmFormat());
                    Assert.assertNotNull(format.getAmPmFormat());
                    Assert.assertNotNull(format.getFormatDateShort());
                    Assert.assertNotNull(format.getFormatHour());
                    Assert.assertNotNull(format.getFormatMonthYear());
                    Assert.assertNotNull(format.getFormatTime());
                    Assert.assertNotNull(format.getMonths());
                    Assert.assertNotNull(format.getWeekdays());
                }
                catch (Exception e)
                {
                    System.out.println("Missing properties for " + localeString);
                }
            }
        }

    }
}
