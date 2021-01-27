package edu.umich.andykong.ptmshepherd.specsimilarity;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Arrays;

import edu.umich.andykong.ptmshepherd.core.FastLocator;
import edu.umich.andykong.ptmshepherd.localization.LocalizationRecord;

public class SimRTProfile {
	public SimRTRecord [] records;
	
	public FastLocator locate;
	double [] masses;
	double [][] peaks;
	double peakTol;
	int precursorUnits;
	
	public SimRTProfile(double [][] peakVals, double peakTol, int precursorUnits) {
		masses = Arrays.copyOf(peakVals[0], peakVals[0].length);
		peaks = peakVals;
		this.peakTol = peakTol;
		this.precursorUnits = precursorUnits;
		locate = new FastLocator(peaks, peakTol, precursorUnits);
		records = new SimRTRecord[masses.length];
		for(int i = 0; i < masses.length; i++)
			records[i] = new SimRTRecord(masses[i], i);
	}
	
	public void writeProfile(String path) throws Exception {
		PrintWriter out = new PrintWriter(new FileWriter(path));
		out.printf("%s\t%s\t%s\t%s\t%s\t%s\n",
				"Peak","Matched PSMs","Similarity (mean)","Similarity (variance)","DeltaRT (mean)", "DeltaRT (variance)");
		for(int i = 0; i < records.length; i++) {
			out.println(records[i].toString());
		}
		out.close();
	}

}
