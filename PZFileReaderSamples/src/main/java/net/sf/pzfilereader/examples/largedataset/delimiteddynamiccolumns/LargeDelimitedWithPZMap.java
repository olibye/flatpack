package net.sf.pzfilereader.examples.largedataset.delimiteddynamiccolumns;

/*
 * Created on Dec 31, 2004
 *
 */

import java.io.File;
import java.io.FileInputStream;

import net.sf.pzfilereader.DataSet;
import net.sf.pzfilereader.brparse.BuffReaderDelimPZParser;
import net.sf.pzfilereader.brparse.BuffReaderPZParseFactory;

/**
 * @author zepernick
 * 
 * TODO To change the template for this generated type comment go to Window -
 * Preferences - Java - Code Style - Code Templates
 */
public class LargeDelimitedWithPZMap {
    public static void main(final String[] args) throws Exception {
        String mapping = getDefaultMapping();
        String data = getDefaultDataFile();
        call(mapping, data);

    }

    public static String getDefaultDataFile() {
        return "PEOPLE-CommaDelimitedWithQualifier.txt";
    }

    public static String getDefaultMapping() {
        return "PEOPLE-Delimited.pzmap.xml";
    }

    public static void call(String mapping, String data) throws Exception {
        String[] colNames = null;
        FileInputStream pzmap = null;
        FileInputStream fileToParse = null;
        BuffReaderDelimPZParser pzparse = null;
        try {
            pzmap = new FileInputStream(new File(mapping));
            fileToParse = new FileInputStream(new File(data));
            // delimited by a comma
            // text qualified by double quotes
            // ignore first record
            
            pzparse = (BuffReaderDelimPZParser)BuffReaderPZParseFactory.getInstance().newDelimitedParser(pzmap, 
                    fileToParse, ',', '"', true);
    
            final DataSet ds = pzparse.parse();
    
            colNames = ds.getColumns();
    
            while (ds.next()) {
                for (int i = 0; i < colNames.length; i++) {
                    System.out.println("COLUMN NAME: " + colNames[i] + " VALUE: " + ds.getString(colNames[i]));
                }
    
                System.out.println("===========================================================================");
            }
    
            if (ds.getErrors() != null && ds.getErrors().size() > 0) {
                System.out.println("FOUND ERRORS IN FILE");
            }
            
        } finally {
            // free up the file readers
            pzparse.close();
            
        }

    }
}
