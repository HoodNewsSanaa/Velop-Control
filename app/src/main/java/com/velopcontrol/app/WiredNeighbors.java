package com.velopcontrol.app;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Extract only LLDP identity/port fields. Full system diagnostics are never retained. */
public final class WiredNeighbors {
    public static final class Neighbor {
        public String localInterface="", chassisMac="", managementIp="", remotePort="";
    }
    public final List<Neighbor> neighbors=new ArrayList<>();
    public boolean sectionFound;
    public static WiredNeighbors parse(InputStream input) throws IOException {
        WiredNeighbors report=new WiredNeighbors();Neighbor current=null;
        // Bound both total input and line size, including sections that we discard.
        Reader reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8));
        StringBuilder line=new StringBuilder();int count=0;boolean longLine=false;int ch;
        while((ch=reader.read())!=-1){
            if(++count>2*1024*1024)throw new IOException("System-information response exceeded 2 MiB");
            if(ch!='\n'){if(line.length()<2048)line.append((char)ch);else longLine=true;continue;}
            String text=longLine?"":line.toString().trim();line.setLength(0);longLine=false;
            if(text.contains("LLDP Information")&&text.startsWith("====")){report.sectionFound=true;continue;}
            if(!report.sectionFound)continue;
            if(text.contains("End of Sysinfo Output")||text.startsWith("================"))break;
            if(text.startsWith("Interface:")){
                if(report.neighbors.size()>=64)break;
                String name=text.substring(10).split(",",2)[0].trim();
                if(!name.matches("[A-Za-z0-9_.:-]{1,40}")){current=null;continue;}
                current=new Neighbor();current.localInterface=name;report.neighbors.add(current);
            }else if(current!=null){
                if(text.startsWith("ChassisID:")){
                    String mac=text.substring(10).trim().replaceFirst("^mac\\s+","");
                    if(mac.matches("(?i)[a-f0-9]{2}(:[a-f0-9]{2}){5}"))current.chassisMac=mac.toUpperCase(Locale.ROOT);
                }else if(text.startsWith("MgmtIP:")){
                    String ip=text.substring(7).trim();try{current.managementIp=ReadPolicy.privateAddress(ip);}catch(IllegalArgumentException ignored){}
                }else if(text.startsWith("PortID:")){
                    String port=text.substring(7).trim();
                    if(port.matches("[A-Za-z0-9_.:/ -]{1,100}"))current.remotePort=port;
                }
            }
        }
        return report;
    }
    private WiredNeighbors() {}
}
