/*
 * JasperReports - Free Java Reporting Library.
 * Copyright (C) 2001 - 2025 Cloud Software Group, Inc. All rights reserved.
 * http://www.jaspersoft.com
 *
 * Unless you have purchased a commercial license agreement from Jaspersoft,
 * the following license terms apply:
 *
 * This program is part of JasperReports.
 *
 * JasperReports is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JasperReports is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with JasperReports. If not, see <http://www.gnu.org/licenses/>.
 */
package net.sf.jasperreports.engine.util;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.sf.jasperreports.engine.JRPropertiesUtil;
import net.sf.jasperreports.engine.JRPropertiesUtil.PropertySuffix;
import net.sf.jasperreports.engine.JRRuntimeException;
import net.sf.jasperreports.engine.JasperReportsContext;

/**
 * Class filter applied when deserializing objects, backported from JasperReports
 * 7.0.4/7.0.7 to address CVE-2026-6009 (CWE-502).
 * <p>
 * Only classes matching one of the configured whitelists are allowed to be
 * resolved by the deserialization streams. Two independent whitelists are
 * maintained: one for the object graphs read by
 * {@link ContextClassLoaderObjectInputStream} and
 * {@link net.sf.jasperreports.engine.fill.VirtualizationObjectInputStream}, and a
 * much narrower one for the report values deserialized by
 * {@link JRValueStringUtils}.
 *
 * @see #PROPERTY_CLASS_FILTER_ENABLED
 */
public class JRDeserializationFilter
{
	/**
	 * Property that determines whether deserialization class filtering is enabled.
	 * <p>
	 * Defaults to <code>true</code>. Setting it to <code>false</code> restores the
	 * unfiltered 6.21.5 behaviour and re-exposes CVE-2026-6009.
	 */
	public static final String PROPERTY_CLASS_FILTER_ENABLED =
			JRPropertiesUtil.PROPERTY_PREFIX + "deserialization.class.filter.enabled";

	/**
	 * Prefix of the properties holding the general deserialization whitelist.
	 */
	public static final String PROPERTY_PREFIX_CLASS_WHITELIST =
			JRPropertiesUtil.PROPERTY_PREFIX + "deserialization.class.whitelist.";

	/**
	 * Prefix of the properties holding the report value deserialization whitelist.
	 */
	public static final String PROPERTY_PREFIX_VALUE_CLASS_WHITELIST =
			JRPropertiesUtil.PROPERTY_PREFIX + "value.deserialization.class.whitelist.";

	public static final String EXCEPTION_MESSAGE_KEY_CLASS_NOT_VISIBLE = "deserialization.class.not.visible";

	public static final String EXCEPTION_MESSAGE_KEY_VALUE_CLASS_NOT_VISIBLE = "value.deserialization.class.not.visible";

	/**
	 * Returns a filter guarding the general object deserialization streams.
	 * <p>
	 * A new instance is built per stream, as upstream does. Filters are not cached
	 * because {@link JasperReportsContext} properties are mutable: a cached filter
	 * would keep applying the whitelist as it stood when the first stream was
	 * opened, silently ignoring later changes.
	 */
	public static JRDeserializationFilter getObjectFilter(JasperReportsContext jasperReportsContext)
	{
		return new JRDeserializationFilter(jasperReportsContext, PROPERTY_PREFIX_CLASS_WHITELIST);
	}

	/**
	 * Returns a filter guarding {@link JRValueStringUtils} value deserialization.
	 */
	public static JRDeserializationFilter getValueFilter(JasperReportsContext jasperReportsContext)
	{
		return new JRDeserializationFilter(jasperReportsContext, PROPERTY_PREFIX_VALUE_CLASS_WHITELIST);
	}

	private final boolean filterEnabled;
	private final String messageKey;
	private final StandardClassWhitelist whitelist;
	private final Map<String, Boolean> visibilityCache = new ConcurrentHashMap<String, Boolean>();

	protected JRDeserializationFilter(JasperReportsContext jasperReportsContext, String whitelistPrefix)
	{
		JRPropertiesUtil properties = JRPropertiesUtil.getInstance(jasperReportsContext);
		this.filterEnabled = properties.getBooleanProperty(PROPERTY_CLASS_FILTER_ENABLED, true);
		this.messageKey = PROPERTY_PREFIX_VALUE_CLASS_WHITELIST.equals(whitelistPrefix)
				? EXCEPTION_MESSAGE_KEY_VALUE_CLASS_NOT_VISIBLE
				: EXCEPTION_MESSAGE_KEY_CLASS_NOT_VISIBLE;

		if (filterEnabled)
		{
			whitelist = new StandardClassWhitelist();
			if (PROPERTY_PREFIX_CLASS_WHITELIST.equals(whitelistPrefix))
			{
				addHardcodedWhitelist(whitelist);
			}

			List<PropertySuffix> whitelistProperties = properties.getProperties(whitelistPrefix);
			for (PropertySuffix propertySuffix : whitelistProperties)
			{
				String whitelistString = propertySuffix.getValue();
				if (whitelistString != null)
				{
					whitelist.addWhitelist(whitelistString);
				}
			}
		}
		else
		{
			whitelist = null;
		}
	}

	public boolean isFilteringEnabled()
	{
		return filterEnabled;
	}

	/**
	 * Checks that the class is allowed to be deserialized, throwing an exception
	 * if it is not.
	 */
	public void checkClassVisibility(String className) throws JRRuntimeException
	{
		if (!isClassVisible(className))
		{
			throw new JRRuntimeException(messageKey, new Object[] {className});
		}
	}

	public boolean isClassVisible(String className)
	{
		if (!filterEnabled)
		{
			return true;
		}

		String elementClassName = arrayElementClassName(className);
		Boolean visible = visibilityCache.get(elementClassName);
		if (visible == null)
		{
			visible = whitelist.includesClass(elementClassName);
			visibilityCache.put(elementClassName, visible);
		}
		return visible;
	}

	/**
	 * Strips the array notation from a serialized class name so that arrays are
	 * checked against the whitelist by their element type. Primitive arrays
	 * collapse to their single letter type code, matched by the hardcoded
	 * whitelist.
	 */
	protected static String arrayElementClassName(String className)
	{
		if (className == null || className.isEmpty() || className.charAt(0) != '[')
		{
			return className;
		}

		if (className.endsWith(";"))
		{
			return className.substring(className.lastIndexOf("[L") + 2, className.length() - 1);
		}

		// primitive array such as [I or [[B - collapses to the type code
		return className.substring(className.lastIndexOf('[') + 1);
	}

	/**
	 * Classes always allowed by the object deserialization filter, mirroring
	 * DeserializationClassFilter.addExtraWhitelists() in 7.0.7, plus the char
	 * array type code C which upstream leaves commented out.
	 */
	protected static void addHardcodedWhitelist(StandardClassWhitelist whitelist)
	{
		whitelist.addClass("B");
		// upstream leaves C commented out, but char[] carries no readObject and does
		// reach print elements that retain their original evaluated value
		whitelist.addClass("C");
		whitelist.addClass("D");
		whitelist.addClass("F");
		whitelist.addClass("I");
		whitelist.addClass("J");
		whitelist.addClass("S");
		whitelist.addClass("Z");
		whitelist.addClass("java.lang.Boolean");
		whitelist.addClass("java.lang.Byte");
		whitelist.addClass("java.lang.Character");
		whitelist.addClass("java.lang.Double");
		whitelist.addClass("java.lang.Enum");
		whitelist.addClass("java.lang.Float");
		whitelist.addClass("java.lang.Integer");
		whitelist.addClass("java.lang.Long");
		whitelist.addClass("java.lang.Number");
		whitelist.addClass("java.lang.Object");
		whitelist.addClass("java.lang.Short");
		whitelist.addClass("java.lang.String");
	}
}
