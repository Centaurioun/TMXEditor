/*******************************************************************************
 * Copyright (c) 2023 Maxprograms.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 1.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-v10.html
 *
 * Contributors:
 *     Maxprograms - initial API and implementation
 *******************************************************************************/

package com.maxprograms.tmxserver.tmx;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.xml.parsers.ParserConfigurationException;

import com.maxprograms.tmxserver.Constants;
import com.maxprograms.tmxserver.excel.ExcelWriter;
import com.maxprograms.tmxserver.excel.Sheet;
import com.maxprograms.tmxserver.models.Language;
import com.maxprograms.tmxserver.models.TUnit;
import com.maxprograms.tmxserver.utils.TextUtils;
import com.maxprograms.xml.Document;
import com.maxprograms.xml.Element;
import com.maxprograms.xml.Indenter;
import com.maxprograms.xml.SAXBuilder;

import org.xml.sax.SAXException;

public class SimpleStore implements StoreInterface {

	private long time;
	private Element header;
	private Set<String> languages;
	private long discarded;
	private int saved;
	private Map<String, Element> tus;
	private List<String> order;
	private Map<String, Map<String, Element>> maps;
	private SAXBuilder builder;
	private FileOutputStream out;
	private long processed;
	private long exported;
	private int indentation;

	public SimpleStore() {
		time = System.currentTimeMillis();
		languages = new TreeSet<>();
		discarded = 0;
		maps = new HashMap<>();
		tus = new HashMap<>();
		order = new ArrayList<>();
	}

	@Override
	public void storeTU(Element element) {
		String id = "" + time++;
		List<Element> tuvs = element.getChildren("tuv");
		Iterator<Element> it = tuvs.iterator();
		int tuvCount = 0;
		while (it.hasNext()) {
			Element tuv = it.next();
			String lang = tuv.getAttributeValue("xml:lang", "");
			if (lang.isEmpty()) {
				lang = tuv.getAttributeValue("lang", "");
				if (!lang.isEmpty()) {
					tuv.setAttribute("xml:lang", lang);
					tuv.removeAttribute("lang");
				}
			}
			if (lang.isEmpty()) {
				// ignore this one
				continue;
			}
			if (!languages.contains(lang)) {
				languages.add(lang);
				Map<String, Element> map = new HashMap<>();
				maps.put(lang, map);
			}
			storeTuv(lang, id, tuv);
			tuvCount++;
		}
		if (tuvCount > 0) {
			element.removeChild("tuv");
			tus.put(id, element);
			order.add(id);
		} else {
			discarded++;
		}
	}

	private void storeTuv(String lang, String id, Element tuv) {
		maps.get(lang).put(id, tuv);
	}

	@Override
	public void storeHeader(Element value) {
		header = value;
	}

	@Override
	public Element getHeader() {
		return header;
	}

	@Override
	public Set<String> getLanguages() {
		return languages;
	}

	@Override
	public List<TUnit> getUnits(long start, int count, String filterText, Language filterLanguage,
			boolean caseSensitive, boolean filterUntranslated, boolean regExp, Language filterSrcLanguage,
			Language sortLanguage, boolean ascending) throws IOException {
		processed = 0;
		List<TUnit> result = new ArrayList<>();
		Iterator<String> ut = order.iterator();
		if (filterText == null && !filterUntranslated) {
			for (long i = 0; i < tus.size(); i++) {
				String id = ut.next();
				Map<String, String> map = new HashMap<>();
				Iterator<String> it = languages.iterator();
				while (it.hasNext()) {
					String lang = it.next();
					map.put(lang, getTuv(id, lang, null, caseSensitive, regExp));
				}
				result.add(new TUnit(1 + i, id, map));
				processed++;
			}
		} else if (filterText != null && !filterText.isEmpty()) {
			String srclang = filterUntranslated ? filterSrcLanguage.getCode() : "";
			for (long i = 0; i < tus.size(); i++) {
				String id = ut.next();
				if (filterUntranslated && !isUntranslated(id, srclang)) {
					continue;
				}
				String seg = getTuv(id, filterLanguage.getCode(), filterText, caseSensitive, regExp);
				if (seg.indexOf(TmxUtils.STYLE) != -1) {
					Map<String, String> map = new HashMap<>();
					Iterator<String> it = languages.iterator();
					while (it.hasNext()) {
						String lang = it.next();
						if (lang.equals(filterLanguage.getCode())) {
							map.put(lang, getTuv(id, lang, filterText, caseSensitive, regExp));
						} else {
							map.put(lang, getTuv(id, lang, null, caseSensitive, regExp));
						}
					}
					result.add(new TUnit(processed + 1, id, map));
				}
				processed++;
			}
		} else if (filterUntranslated) {
			String srclang = filterSrcLanguage.getCode();
			for (long i = 0; i < tus.size(); i++) {
				String id = ut.next();
				if (isUntranslated(id, srclang)) {
					Map<String, String> map = new HashMap<>();
					Iterator<String> it = languages.iterator();
					while (it.hasNext()) {
						String lang = it.next();
						map.put(lang, getTuv(id, lang, null, caseSensitive, regExp));
					}
					result.add(new TUnit(processed + 1, id, map));
				}
				processed++;
			}
		} else {
			throw new IOException("Wrong filtering option");
		}

		if (sortLanguage != null) {
			Collections.sort(result, new Comparator<TUnit>() {

				@Override
				public int compare(TUnit o1, TUnit o2) {
					String s1 = o1.getString(sortLanguage.getCode());
					String s2 = o2.getString(sortLanguage.getCode());
					if (ascending) {
						return s1.compareTo(s2);
					}
					return s2.compareTo(s1);
				}
			});
		}
		if (result.size() < count) {
			return result;
		}
		List<TUnit> list = new ArrayList<>();
		if (result.size() < start + count) {
			list.addAll(result.subList((int) start, result.size()));
			return list;
		}
		list.addAll(result.subList((int) start, (int) (start + count)));
		return list;
	}

	private boolean isUntranslated(String id, String srclang) throws IOException {
		int count = 0;
		Iterator<String> it = languages.iterator();
		while (it.hasNext()) {
			String lang = it.next();
			if (!lang.equals(srclang)) {
				String seg = getTuv(id, lang, null, false, false);
				if (!seg.isBlank()) {
					count++;
				}
			}
		}
		return count == 0;
	}

	private String getTuv(String id, String lang, String filterText, boolean caseSensitive, boolean regExp)
			throws IOException {
		String result = "";
		Element tuv = maps.get(lang).get(id);
		if (tuv != null) {
			result = TmxUtils.pureText(tuv.getChild("seg"), true, filterText, caseSensitive, regExp);
		}
		return result;
	}

	@Override
	public void close() {
		// do nothing
	}

	@Override
	public long getCount() {
		return tus.size();
	}

	@Override
	public String saveData(String id, String lang, String value) throws IOException {
		Map<String, Element> map = maps.get(lang);
		Element tuv = map.get(id);
		String text = value;
		if (tuv != null) {
			Element seg = tuv.getChild("seg");
			TmxUtils.pureText(seg, true, null, false, false);
			Map<String, String> tags = TmxUtils.getTags();
			Set<String> keys = tags.keySet();
			Iterator<String> it = keys.iterator();
			while (it.hasNext()) {
				String key = it.next();
				text = TextUtils.replaceAll(text, key, tags.get(key), false);
			}
			if (builder == null) {
				builder = new SAXBuilder();
			}
			try {
				Document d = builder
						.build(new ByteArrayInputStream(("<seg>" + text + "</seg>").getBytes(StandardCharsets.UTF_8)));
				seg.setContent(d.getRootElement().getContent());
			} catch (Exception ex) {
				seg.setText(text);
			}
		} else {
			tuv = new Element("tuv");
			tuv.setAttribute("xml:lang", lang);
			tuv.setAttribute("creationdate", TmxUtils.tmxDate());
			Element seg = new Element("seg");
			seg.setText(text);
			tuv.addContent(seg);
		}
		map.put(id, tuv);
		return TmxUtils.pureText(tuv.getChild("seg"), true, null, false, false);
	}

	@Override
	public long getDiscarded() {
		return discarded;
	}

	@Override
	public void writeFile(File file) throws IOException {
		saved = 0;
		out = new FileOutputStream(file);
		writeString("<?xml version=\"1.0\" ?>\r\n"
				+ "<!DOCTYPE tmx PUBLIC \"-//LISA OSCAR:1998//DTD for Translation Memory eXchange//EN\" \"tmx14.dtd\">\r\n"
				+ "<tmx version=\"1.4\">\n");
		writeString(TextUtils.padding(1, indentation) + header.toString() + "\n");
		writeString(TextUtils.padding(1, indentation) + "<body>\n");
		Iterator<String> tuIt = order.iterator();
		while (tuIt.hasNext()) {
			String tuid = tuIt.next();
			Element tu = tus.get(tuid);
			Iterator<String> langIt = languages.iterator();
			tu.removeChild("tuv");
			while (langIt.hasNext()) {
				String lang = langIt.next();
				Element tuv = maps.get(lang).get(tuid);
				if (tuv != null) {
					tu.addContent(tuv);
				}
			}
			if (tu.getChildren().isEmpty()) {
				continue;
			}
			Indenter.indent(tu, 3, indentation);
			writeString(TextUtils.padding(2, indentation) + tu.toString() + "\n");
			saved++;
		}
		writeString(TextUtils.padding(1, indentation) + "</body>\n");
		writeString("</tmx>");
		out.close();
	}

	private void writeString(String string) throws IOException {
		out.write(string.getBytes(StandardCharsets.UTF_8));
	}

	@Override
	public int getSaved() {
		return saved;
	}

	@Override
	public void commit() {
		// nothing to do
	}

	@Override
	public Element getTu(String id) {
		return tus.get(id);
	}

	@Override
	public void delete(List<String> selected) {
		Iterator<String> it = selected.iterator();
		while (it.hasNext()) {
			delete(it.next());
		}
	}

	@Override
	public void replaceText(String search, String replace, Language language, boolean regExp) {
		processed = 0l;
		Iterator<String> ut = order.iterator();
		Map<String, Element> langsMap = maps.get(language.getCode());
		while (ut.hasNext()) {
			String id = ut.next();
			Element tuv = langsMap.get(id);
			String segText = TmxUtils.textOnly(tuv.getChild("seg"));
			if (regExp) {
				TmxUtils.replaceText(tuv.getChild("seg"), search, replace, regExp);
				langsMap.put(id, tuv);
			} else {
				if (segText.indexOf(search) != -1) {
					TmxUtils.replaceText(tuv.getChild("seg"), search, replace, regExp);
					langsMap.put(id, tuv);
				}
			}
			processed++;
		}
	}

	@Override
	public long getProcessed() {
		return processed;
	}

	@Override
	public void insertUnit(String id) {
		Element tu = new Element("tu");
		tu.setAttribute("tuid", id);
		tu.setAttribute("creationdate", TmxUtils.tmxDate());
		tu.setAttribute("creationid", System.getProperty("user.name"));
		tu.setAttribute("creationtool", Constants.APPNAME);
		tu.setAttribute("creationtoolversion", Constants.VERSION);
		tus.put(id, tu);
		order.add(id);
	}

	@Override
	public long removeUntranslated(Language language) throws IOException {
		processed = 0l;
		List<String> selected = new ArrayList<>();
		String srclang = language.getCode();
		Iterator<String> ut = order.iterator();
		while (ut.hasNext()) {
			String id = ut.next();
			if (isUntranslated(id, srclang)) {
				selected.add(id);
			}
			processed++;
		}
		long result = selected.size();
		Iterator<String> it = selected.iterator();
		while (it.hasNext()) {
			delete(it.next());
		}
		selected.clear();
		return result;
	}

	@Override
	public void removeSameAsSource(Language language) throws IOException {
		processed = 0l;
		List<String> selected = new ArrayList<>();
		String srclang = language.getCode();
		Iterator<String> ut = order.iterator();
		while (ut.hasNext()) {
			String tuid = ut.next();
			Element srcTuv = maps.get(srclang).get(tuid);
			if (srcTuv != null) {
				Element src = srcTuv.getChild("seg");
				Iterator<String> langIt = languages.iterator();
				int count = 0;
				while (langIt.hasNext()) {
					String lang = langIt.next();
					if (!lang.equals(srclang)) {
						Element tuv = maps.get(lang).get(tuid);
						if (tuv != null) {
							Element tgt = tuv.getChild("seg");
							if (src.equals(tgt)) {
								maps.get(lang).remove(tuid);
							} else {
								count++;
							}
						}
					}
				}
				if (count == 0) {
					selected.add(tuid);
				}
			}
			processed++;
		}
		Iterator<String> it = selected.iterator();
		while (it.hasNext()) {
			delete(it.next());
		}
		selected.clear();
	}

	@Override
	public void addLanguage(Language language) {
		String lang = language.getCode();
		if (!languages.contains(lang)) {
			languages.add(lang);
			Map<String, Element> map = new HashMap<>();
			maps.put(lang, map);
		}
	}

	@Override
	public void removeLanguage(Language language) {
		String lang = language.getCode();
		if (languages.contains(lang)) {
			maps.get(lang).clear();
			maps.remove(lang);
			languages.remove(lang);
		}
	}

	@Override
	public void removeTags() {
		processed = 0l;
		Iterator<String> ut = order.iterator();
		while (ut.hasNext()) {
			String id = ut.next();
			Iterator<String> it = languages.iterator();
			while (it.hasNext()) {
				String lang = it.next();
				Element tuv = maps.get(lang).get(id);
				if (tuv != null) {
					Element seg = tuv.getChild("seg");
					if (!seg.getChildren().isEmpty()) {
						seg.setText(TmxUtils.textOnly(seg));
						maps.get(lang).put(id, tuv);
					}
				}
			}
			processed++;
		}
	}

	@Override
	public void changeLanguage(Language oldLanguage, Language newLanguage) {
		String newCode = newLanguage.getCode();
		Map<String, Element> map = maps.get(oldLanguage.getCode());
		Set<String> keySet = map.keySet();
		Iterator<String> it = keySet.iterator();
		while (it.hasNext()) {
			String id = it.next();
			map.get(id).setAttribute("xml:lang", newCode);
			processed++;
		}
		maps.remove(oldLanguage.getCode());
		maps.put(newCode, map);
		languages.add(newCode);
		languages.remove(oldLanguage.getCode());
	}

	@Override
	public void removeDuplicates() {
		List<String> langs = new ArrayList<>();
		Iterator<String> it = languages.iterator();
		while (it.hasNext()) {
			langs.add(it.next());
		}
		for (int m = 0; m < langs.size() - 1; m++) {
			String srcLang = langs.get(m);
			List<Pair> pairs = new ArrayList<>();
			Map<String, Element> map = maps.get(srcLang);
			Set<String> keySet = map.keySet();
			it = keySet.iterator();
			while (it.hasNext()) {
				String id = it.next();
				String text = makeText(map.get(id));
				if (!text.isEmpty()) {
					Pair p = new Pair(id, text);
					pairs.add(p);
				}
			}
			Collections.sort(pairs);

			processed = 0l;
			Set<String> deleteLater = new TreeSet<>();
			for (int i = 0; i < pairs.size() - 1; i++) {
				String currentId = pairs.get(i).getId();
				for (int j = i + 1; j < pairs.size(); j++) {
					if (!pairs.get(i).getText().equals(pairs.get(j).getText())) {
						break;
					}
					String secondId = pairs.get(j).getId();
					if (deleteLater.contains(secondId)) {
						continue;
					}
					Iterator<String> lt = languages.iterator();
					boolean repeated = true;
					while (lt.hasNext()) {
						String lang = lt.next();
						Element a = maps.get(lang).get(currentId);
						Element b = maps.get(lang).get(secondId);
						if (a != null) {
							if (b == null) {
								repeated = false;
								break;
							}
							Element segA = a.getChild("seg");
							Element segB = b.getChild("seg");
							if (!segA.equals(segB)) {
								repeated = false;
								break;
							}
						} else {
							if (b != null) {
								repeated = false;
								break;
							}
						}
					}
					if (repeated) {
						deleteLater.add(secondId);
					}
				}
				processed++;
			}

			Iterator<String> idlt = deleteLater.iterator();
			while (idlt.hasNext()) {
				delete(idlt.next());
			}
		}
	}

	private void delete(String id) {
		Iterator<String> lt = languages.iterator();
		while (lt.hasNext()) {
			maps.get(lt.next()).remove(id);
		}
		tus.remove(id);
		order.remove(id);
	}

	@Override
	public void removeSpaces() throws SAXException, IOException, ParserConfigurationException {
		processed = 0l;
		Iterator<String> ut = order.iterator();
		while (ut.hasNext()) {
			String id = ut.next();
			Iterator<String> it = languages.iterator();
			while (it.hasNext()) {
				String lang = it.next();
				Element tuv = maps.get(lang).get(id);
				if (tuv != null) {
					Element seg = tuv.getChild("seg");
					if (seg != null) {
						seg.setContent(TmxUtils.stripSegment(seg).getContent());
						if (!seg.getText().isEmpty()) {
							maps.get(lang).put(id, tuv);
						} else {
							maps.get(lang).remove(id);
						}
					}
				}
			}
			processed++;
		}
	}

	@Override
	public void consolidateUnits(Language language) throws IOException {
		processed = 0l;
		String srcLang = language.getCode();
		List<Pair> pairs = new ArrayList<>();
		Map<String, Element> map = maps.get(srcLang);
		Set<String> keySet = map.keySet();
		Iterator<String> it = keySet.iterator();
		while (it.hasNext()) {
			String id = it.next();
			String text = makeText(map.get(id));
			if (!text.isEmpty()) {
				Pair p = new Pair(id, text);
				pairs.add(p);
			}
		}
		Collections.sort(pairs);
		int i = 0;
		while (i < pairs.size() - 1) {
			Pair currentPair = pairs.get(i);
			Element currentSeg = map.get(currentPair.getId()).getChild("seg");
			int j = 1;
			Pair nextPair = pairs.get(i + j);
			while (currentPair.getText().equals(nextPair.getText())) {
				Element nextSeg = map.get(nextPair.getId()).getChild("seg");
				if (currentSeg.equals(nextSeg)) {
					Iterator<String> lt = languages.iterator();
					while (lt.hasNext()) {
						String lang = lt.next();
						if (lang.equals(srcLang)) {
							continue;
						}
						Element a = maps.get(lang).get(currentPair.getId());
						Element b = maps.get(lang).get(nextPair.getId());
						if (a == null && b != null) {
							maps.get(lang).put(currentPair.getId(), b);
							maps.get(lang).remove(nextPair.getId());
						}
					}
				}
				j++;
				if (i + j >= pairs.size()) {
					break;
				}
				nextPair = pairs.get(i + j);
			}
			i = i + j;
			processed++;
		}
		removeUntranslated(language);
	}

	private static String makeText(Element tuv) {
		if (tuv == null) {
			return "";
		}
		Element seg = tuv.getChild("seg");
		return TmxUtils.textOnly(seg);
	}

	@Override
	public void setTuAttributes(String id, List<String[]> attributes) {
		Element tu = tus.get(id);
		tu.setAttributes(new ArrayList<>());
		Iterator<String[]> it = attributes.iterator();
		while (it.hasNext()) {
			String[] pair = it.next();
			tu.setAttribute(pair[0], pair[1]);
		}
		tus.put(id, tu);
	}

	@Override
	public void setTuProperties(String id, List<String[]> properties) {
		List<Element> content = new ArrayList<>();
		Iterator<String[]> it = properties.iterator();
		while (it.hasNext()) {
			String[] pair = it.next();
			if (pair[0].isEmpty() || pair[1].isEmpty()) {
				continue;
			}
			Element prop = new Element("prop");
			prop.setAttribute("type", pair[0]);
			prop.setText(pair[1]);
			content.add(prop);
		}
		Element tu = tus.get(id);
		content.addAll(tu.getChildren("note"));
		tu.setChildren(content);
		tus.put(id, tu);
	}

	@Override
	public void setTuNotes(String id, List<String> notes) {
		Element tu = tus.get(id);
		List<Element> content = new ArrayList<>();
		content.addAll(tu.getChildren("prop"));
		Iterator<String> it = notes.iterator();
		while (it.hasNext()) {
			String note = it.next();
			if (note.isEmpty()) {
				continue;
			}
			Element not = new Element("note");
			not.setText(note);
			content.add(not);
		}
		tu.setChildren(content);
		tus.put(id, tu);
	}

	@Override
	public void exportDelimited(String file) throws IOException {
		exported = 0l;
		try (FileOutputStream stream = new FileOutputStream(file);
				OutputStreamWriter cout = new OutputStreamWriter(stream, StandardCharsets.UTF_16LE)) {
			byte[] feff = { -1, -2 };
			stream.write(feff);

			StringBuilder langs = new StringBuilder();
			Iterator<String> it = languages.iterator();
			while (it.hasNext()) {
				if (!langs.toString().isEmpty()) {
					langs.append('\t');
				}
				langs.append(it.next());
			}
			langs.append('\n');
			cout.write(langs.toString());

			exported = 0;

			Iterator<String> tuIt = order.iterator();
			while (tuIt.hasNext()) {
				StringBuilder line = new StringBuilder();
				String tuid = tuIt.next();
				Iterator<String> langIt = languages.iterator();
				while (langIt.hasNext()) {
					String lang = langIt.next();
					Element tuv = maps.get(lang).get(tuid);
					String text = " ";
					if (tuv != null) {
						text = TmxUtils.cleanLines(TmxUtils.textOnly(tuv.getChild("seg")));
					}
					if (!line.toString().isEmpty()) {
						line.append('\t');
					}
					line.append(text);
				}
				line.append('\n');
				cout.write(line.toString());
				exported++;
			}
		}
	}

	@Override
	public void exportExcel(String file) throws IOException, SAXException, ParserConfigurationException {
		exported = 0l;
		Map<String, String> langsMap = new HashMap<>();
		Set<String> cols = new TreeSet<>();
		int i = 0;
		Iterator<String> it = languages.iterator();
		while (it.hasNext()) {
			String lang = it.next();
			char c = (char) (65 + i++);
			cols.add("" + c);
			langsMap.put(lang, "" + c);
		}

		List<Map<String, String>> rows = new ArrayList<>();
		Map<String, String> firstRow = new HashMap<>();
		Iterator<String> langIt = languages.iterator();
		while (langIt.hasNext()) {
			String lang = langIt.next();
			firstRow.put(langsMap.get(lang), lang);
		}
		rows.add(firstRow);
		Iterator<String> tuIt = order.iterator();
		while (tuIt.hasNext()) {
			String tuid = tuIt.next();
			Map<String, String> rowMap = new HashMap<>();
			langIt = languages.iterator();
			while (langIt.hasNext()) {
				String lang = langIt.next();
				Element tuv = maps.get(lang).get(tuid);
				String text = "";
				if (tuv != null) {
					text = TmxUtils.textOnly(tuv.getChild("seg"));
				}
				rowMap.put(langsMap.get(lang), text);
			}
			rows.add(rowMap);
			exported++;
		}
		Sheet sheet = new Sheet("Sheet1", cols, rows);
		ExcelWriter writer = new ExcelWriter();
		writer.writeFile(file, sheet);
	}

	@Override
	public long getExported() {
		return exported;
	}

	@Override
	public Element getTuv(String id, String lang) {
		return maps.get(lang).get(id);
	}

	@Override
	public void setTuvAttributes(String id, String lang, List<String[]> attributes) {
		Map<String, Element> map = maps.get(lang);
		Element tuv = map.get(id);
		if (tuv != null) {
			tuv.setAttributes(new ArrayList<>());
			Iterator<String[]> it = attributes.iterator();
			while (it.hasNext()) {
				String[] pair = it.next();
				tuv.setAttribute(pair[0], pair[1]);
			}
			map.put(id, tuv);
		}
	}

	@Override
	public void setTuvProperties(String id, String lang, List<String[]> properties) {
		Map<String, Element> map = maps.get(lang);
		Element tuv = map.get(id);
		if (tuv != null) {
			tuv.removeChild("prop");
			List<Element> content = tuv.getChildren();
			Iterator<String[]> it = properties.iterator();
			while (it.hasNext()) {
				String[] pair = it.next();
				if (pair[0].isEmpty() || pair[1].isEmpty()) {
					continue;
				}
				Element prop = new Element("prop");
				prop.setAttribute("type", pair[0]);
				prop.setText(pair[1]);
				content.add(0, prop);
			}
			tuv.setChildren(content);
			map.put(id, tuv);
		}
	}

	@Override
	public void setTuvNotes(String id, String lang, List<String> notes) {
		Map<String, Element> map = maps.get(lang);
		Element tuv = map.get(id);
		if (tuv != null) {
			tuv.removeChild("note");
			List<Element> content = tuv.getChildren();
			Iterator<String> it = notes.iterator();
			while (it.hasNext()) {
				String note = it.next();
				if (note.isEmpty()) {
					continue;
				}
				Element not = new Element("note");
				not.setText(note);
				content.add(0, not);
			}
			tuv.setChildren(content);
			map.put(id, tuv);
		}
	}

	@Override
	public void setIndentation(int indentation) {
		this.indentation = indentation;
	}
}
