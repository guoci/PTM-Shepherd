package edu.umich.andykong.ptmshepherd.specsimilarity;

import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

import edu.umich.andykong.ptmshepherd.PSMFile;
import edu.umich.andykong.ptmshepherd.PTMShepherd;
import edu.umich.andykong.ptmshepherd.core.MXMLReader;
import edu.umich.andykong.ptmshepherd.core.Spectrum;
import edu.umich.andykong.ptmshepherd.localization.LocalizationProfile;
import java.util.List;

public class SimRTAnalysis {

	String dsName;
	File simRTFile;
	MXMLReader mr;
	HashMap<String, MXMLReader> multiMr;
	double ppmTol, condRatio, peakTol;
	int condPeaks, precursorUnits;
	int specCol, pepCol, modpepCol, chargeCol, deltaCol, rtCol, intCol;
	
	static final int MAX_ZERO_COMPARE = 20;
	
	public SimRTAnalysis(String dsName) {
		this.dsName = dsName;
		this.simRTFile = new File(PTMShepherd.normFName(dsName+".rawsimrt"));
	}
	
	public String reNormName(String s) {
		String [] sp = s.split("\\.");
		int sn = Integer.parseInt(sp[1]);
		//with charge state
		//return String.format("%s.%d.%d.%s",sp[0],sn,sn,sp[3]);
		//without charge state
		return String.format("%s.%d.%d",sp[0],sn,sn);
	}
	
	public boolean isComplete() throws Exception {
		if(simRTFile.exists()) {
			RandomAccessFile raf = new RandomAccessFile(simRTFile, "r");
			raf.seek(Math.max(0, simRTFile.length() - 20));
			String cline;
			while((cline = raf.readLine())!=null)
				if(cline.equals("COMPLETE")) {
					raf.close();
					return true;
				}
			raf.close();
			simRTFile.delete();
		}
		return false;
	}
	
	public void complete() throws Exception {
		PrintWriter out = new PrintWriter(new FileWriter(simRTFile,true));
		out.println("COMPLETE");
		out.close();
	}
	
	public void simrtPSMs(PSMFile pf, HashMap<String,File> mzMappings, boolean interRunComparisons) throws Exception {
		//assemble PSMs into per file groupings
		HashMap<String,ArrayList<Integer>> mappings = new HashMap<>();
		PrintWriter out = new PrintWriter(new FileWriter(simRTFile,true));

		//Write header
		out.printf("%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n","Spectrum","Peptide","Mod_Peptide","Shift","Is_Zero_Pep",
				"rt_shift", "nZeroSpecs_RT_shift", "Avg_Sim", "Avg_ZeroSim", "nZeroSpecs_Sim");

		specCol = pf.getColumn("Spectrum");
		pepCol = pf.getColumn("Peptide");
		modpepCol = pf.getColumn("Modified Peptide");
		chargeCol = pf.getColumn("Charge");
		deltaCol = pf.dMassCol;
		rtCol = pf.getColumn("Retention");
		intCol = pf.getColumn("Intensity");
		
		ppmTol = Double.parseDouble(PTMShepherd.getParam("spectra_ppmtol"));
		condPeaks = Integer.parseInt(PTMShepherd.getParam("spectra_condPeaks"));
		condRatio = Double.parseDouble(PTMShepherd.getParam("spectra_condRatio"));
		peakTol = Double.parseDouble(PTMShepherd.getParam("precursor_tol")); //determines zero bin
		double cPeakTol = peakTol;
		precursorUnits = Integer.parseInt(PTMShepherd.getParam("precursor_mass_units"));

		if (!interRunComparisons) {
			for (int i = 0; i < pf.data.size(); i++) {
				String[] sp = pf.data.get(i).split("\t");
				String bn = sp[specCol].substring(0, sp[specCol].indexOf(".")); //fraction
				if (!mappings.containsKey(bn))
					mappings.put(bn, new ArrayList<>());
				mappings.get(bn).add(i);
			}
		} else {
			for (int i = 0; i < pf.data.size(); i++) {
				String[] sp = pf.data.get(i).split("\t");
				String bn = sp[specCol].substring(0, sp[specCol].indexOf(".")); //fraction
				if (!mappings.containsKey(bn))
					mappings.put(bn, new ArrayList<>());
			}
			for (int i = 0; i < pf.data.size(); i++) {
				for (String fraction : mappings.keySet())
					mappings.get(fraction).add(i);
			}
		}

		//read all files into memory
		multiMr = new HashMap<>();
		long t1 = System.currentTimeMillis();
		for (String cf : mappings.keySet()) {
			mr = new MXMLReader(mzMappings.get(cf), Integer.parseInt(PTMShepherd.getParam("threads")));
			mr.readFully();
			multiMr.put(cf, mr);
		}
		long t2 = System.currentTimeMillis();
		PTMShepherd.print(String.format("\tSpectral data read into memory (%d ms)", t2-t1));


		//itialize these outside of run iteractions for inter run comparisons
		HashMap<String,ArrayList<Integer>> zTolLines = new HashMap<>();
		HashMap<String,ArrayList<Spectrum>> zTolSpecs = new HashMap<>();
		HashMap<String,ArrayList<Double>> zTolRT = new HashMap<>();
		HashMap<String,Double> avgzSim = new HashMap<>(); //{modPep.charge:avg zero sim in bin}
		HashMap<String,Double> avgzRT = new HashMap<>();

		//iterate and calculate similarity/retention time deltas for each file
		//if inter run comparisons is turned on, this look will be broken after first iteration
		for(String cf : mappings.keySet()) { //cf = fraction
			//leaving this here in case we need a less memory intensive application at some point
			//mr = new MXMLReader(mzMappings.get(cf), Integer.parseInt(PTMShepherd.getParam("threads")));
			//mr.readFully();
			ArrayList<Integer> clines = mappings.get(cf);
			//if not performing inter run comparisons, need to reset every time
			if (!interRunComparisons) {
				zTolLines = new HashMap<>();
				zTolSpecs = new HashMap<>();
				zTolRT = new HashMap<>();
				avgzSim = new HashMap<>(); //{modPep.charge:avg zero sim in bin}
				avgzRT = new HashMap<>();
			}

			//get zero bin data and calculate baselines
			for(int i = 0; i < clines.size(); i++) {
				String [] crow = pf.data.get(clines.get(i)).split("\t");
				if(precursorUnits == 1)//ppm
					cPeakTol = calculatePeakTol(1500, peakTol, 0.0);
				boolean isZero = (Math.abs(Double.parseDouble(crow[deltaCol])) <= cPeakTol);
				if(!isZero)
					continue;
				
				String key = crow[pepCol].trim(); //using pep seq as key
				if(crow[modpepCol].trim().length() != 0)
					key = crow[modpepCol].trim();
				
				if(!zTolRT.containsKey(key)) //structure {modpep:<rt>}
					zTolRT.put(key, new ArrayList<>());
				zTolRT.get(key).add(Double.parseDouble(crow[rtCol]));
				
				key += "." + crow[chargeCol]; //structure {modpep.charge:<spec line>}
				if(!zTolLines.containsKey(key))
					zTolLines.put(key, new ArrayList<>());
				zTolLines.get(key).add(clines.get(i));
			}
			
			//calculate zeroSim
			int totalLines = 0;
			List<String> linesWithoutSpectra = new ArrayList<>();
			for(String pepZ : zTolLines.keySet()) {
				ArrayList<Integer> relLines = zTolLines.get(pepZ);
				Collections.shuffle(zTolLines.get(pepZ)); //shuffled to remove bias
				int nComp = Math.min(relLines.size(), MAX_ZERO_COMPARE);
				zTolSpecs.put(pepZ, new ArrayList<>());
				for(int i = 0; i < nComp; i++) {
					String [] crow = pf.data.get(relLines.get(i)).split("\t");
					String targetFrac = crow[specCol].substring(0, crow[specCol].indexOf("."));
					zTolSpecs.get(pepZ).add(multiMr.get(targetFrac).getSpectrum(reNormName(crow[specCol])));
				}
				
				double zSimSum = 0;
				totalLines += relLines.size();
				for(int i = 0; i < relLines.size(); i++) {
					String [] crow = pf.data.get(relLines.get(i)).split("\t");
					String specNormName = reNormName(crow[specCol]);
					//System.out.println(specNormName);
					String targetFrac = crow[specCol].substring(0, crow[specCol].indexOf("."));
					Spectrum cspec = multiMr.get(targetFrac).getSpectrum(specNormName);
					if (cspec == null) {
						linesWithoutSpectra.add(specNormName);
						continue;
					}
					zSimSum += cspec.averageSimilarity(zTolSpecs.get(pepZ), ppmTol);
				}
				avgzSim.put(pepZ, zSimSum / relLines.size());
			}

			if (!linesWithoutSpectra.isEmpty()) {
				System.out.printf("Could not find %d/%d (%.1f%%) spectra.\n", linesWithoutSpectra.size(), totalLines,
						100.0*((double)linesWithoutSpectra.size()/totalLines));
				int previewSize = Math.min(linesWithoutSpectra.size(), 5);
				System.out.printf("Showing first %d of %d spectra IDs that could not be found: \n\t%s\n", previewSize, linesWithoutSpectra.size(),
						String.join("\n\t", linesWithoutSpectra.subList(0, previewSize)));
			}
			
			//calculate zeroRT
			for(String pep : zTolRT.keySet()) {
				ArrayList<Double> rts = zTolRT.get(pep);
				double rtsum = 0;
				for(double v : rts)
					rtsum += v;
				avgzRT.put(pep, rtsum / rts.size());
			}
			
			//calculate metrics
			for(int i = 0; i < clines.size(); i++) {
				String [] crow = pf.data.get(clines.get(i)).split("\t");
				if(precursorUnits == 1)//ppm
					cPeakTol = calculatePeakTol(1500, peakTol, 0.0);
				boolean isZero = (Math.abs(Double.parseDouble(crow[deltaCol])) <= cPeakTol);
				
				String key = crow[pepCol].trim();
				if(crow[modpepCol].trim().length() != 0)
					key = crow[modpepCol].trim();

				int rtSize = 0, specSimSize = 0;
				double rtDelta = -1e10;
				double avgSim = -1e10, avgZeroSim = -1e10;
				
				if(zTolRT.containsKey(key)) { //calculated against average RT time
					rtDelta = Double.parseDouble(crow[rtCol]) - avgzRT.get(key);
					rtSize = zTolRT.get(key).size();
				}
				
				key += "." + crow[chargeCol]; //based on charge state
				if(zTolSpecs.containsKey(key)) {
					String targetFrac = crow[specCol].substring(0, crow[specCol].indexOf("."));
					Spectrum cspec = multiMr.get(targetFrac).getSpectrum(reNormName(crow[specCol]));
					if(cspec != null) {
						avgSim = cspec.averageSimilarity(zTolSpecs.get(key), ppmTol); //all v all comparison
						avgZeroSim = avgzSim.get(key);
						specSimSize = zTolSpecs.get(key).size();
					}
				}
				out.printf("%s\t%s\t%s\t%s\t%d\t%.5f\t%d\t%.5f\t%.5f\t%d\n",crow[specCol],crow[pepCol],crow[modpepCol],crow[deltaCol],isZero?1:0,
						rtDelta, rtSize, avgSim, avgZeroSim, specSimSize);
			}
			
			out.flush();
			long t3 = System.currentTimeMillis();

			if (interRunComparisons) {
				PTMShepherd.print(String.format("\tProcessed - %d (%d ms)", clines.size(), t3-t2));
				break;
			}
			PTMShepherd.print(String.format("\t%s - %d (%d ms)", cf, clines.size(), t3-t2));
		}
		
		out.close();
	}
	
	public void updateSimRTProfiles(SimRTProfile [] profiles) throws Exception {
		BufferedReader in = new BufferedReader(new FileReader(simRTFile));
		String cline;
		in.readLine();
		while((cline = in.readLine())!= null) {
			if(cline.equals("COMPLETE"))
				break;
			if(cline.startsWith("Spectrum"))
				continue;
			String [] sp = cline.split("\\t");
			double md = Double.parseDouble(sp[3]);
			for(int i = 0; i < profiles.length; i++) {
				int cind = profiles[i].locate.getIndex(md);
				if(cind != -1) 
					profiles[i].records[cind].updateWithLine(sp);
			}
		}
		in.close();
	}

	public double calculatePeakTol(double pepmass, double ppmtol, double modmass){
		double peakTol = ((pepmass + modmass) / 1000000.0) * ppmtol;
		return peakTol;
	}
}
