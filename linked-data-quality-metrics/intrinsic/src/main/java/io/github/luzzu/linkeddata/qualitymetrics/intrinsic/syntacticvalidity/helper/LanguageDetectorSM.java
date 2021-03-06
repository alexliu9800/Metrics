package io.github.luzzu.linkeddata.qualitymetrics.intrinsic.syntacticvalidity.helper;

import java.util.ArrayList;

import com.cybozu.labs.langdetect.Detector;
import com.cybozu.labs.langdetect.DetectorFactory;
import com.cybozu.labs.langdetect.LangDetectException;
import com.cybozu.labs.langdetect.Language;

import io.github.luzzu.linkeddata.qualitymetrics.intrinsic.syntacticvalidity.CorrectLanguageTag;

public class LanguageDetectorSM {

	//https://shuyo.wordpress.com/2011/11/28/language-detection-supported-17-language-profiles-for-short-messages/
	private static LanguageDetectorSM instance = null;
	private String profilesDir = "";
	

	
	protected LanguageDetectorSM() throws LangDetectException{ 
		profilesDir = CorrectLanguageTag.class.getClassLoader().getResource("languageprofiles/profiles.sm/").getFile();
	}
	
	public static LanguageDetectorSM getInstance() throws LangDetectException {
		if (instance == null) instance = new LanguageDetectorSM();
		return instance;
	}
	
	public String identifyLanguageOfLabel(String text, Double languageThresholdConfidence) throws LangDetectException {
		DetectorFactory.clear();
		DetectorFactory.loadProfile(profilesDir);
		Detector detector = DetectorFactory.create();
		detector.append(text);
		
		try {
			ArrayList<Language> prob = detector.getProbabilities();
			
			final Language highestConfidence = new Language("", 0);
			
			prob.forEach(l -> {
				if (l.prob >= languageThresholdConfidence) {
					if (l.prob > highestConfidence.prob) {
						highestConfidence.lang = l.lang;
						highestConfidence.prob = l.prob;
					}
				}
			});
		    return highestConfidence.lang;
		} catch (LangDetectException e) {
			e.printStackTrace();
			return "";
		}
	}
}
