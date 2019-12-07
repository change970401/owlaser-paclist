package com.owlaser.paclist.service;

import com.owlaser.paclist.dao.PacDao;
import com.owlaser.paclist.entity.Dependency;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.jsoup.Jsoup;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PacService {
    @Autowired
    private PacDao pacDao;

    public void ScanPacJar(InputStream input, String fileName, List<Dependency> dependenciesList) {
        SAXReader reader = new SAXReader();
        try {
            // 通过reader对象的read方法加载xml文件 ，获取docuement对象
            Document document = reader.read(input);
            // 通过document对象获取根节点root
            ReadPom(document,dependenciesList);
        } catch (DocumentException e ) { //捕获input为空时抛出的异常
            SetNoPomDependencies(dependenciesList, fileName);
        }
        catch (Exception ex ) { //捕获其他异常
            ex.printStackTrace();
        }
    }

    public void ScanPacPom(String filepath, List<Dependency> dependenciesList) {
        SAXReader reader = new SAXReader();
        File pom = new File(filepath);
        try {
            // 通过reader对象的read方法加载xml文件 ，获取docuement对象
            Document document = reader.read(filepath);
            // 通过document对象获取根节点root
            ReadPom(document,dependenciesList);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void ReadPom(Document document, List<Dependency> dependenciesList) {
        Element root = document.getRootElement();
        if (root.element("dependencyManagement") != null) {
            Element dependencyManagement = root.element("dependencyManagement").element("dependencies");
            GetDependencies(dependencyManagement, dependenciesList);
        }
        Element dependencies = root.element("dependencies");
        GetDependencies(dependencies, dependenciesList);
    }

    //得到pom文件里的依赖包信息
    public void GetDependencies(Element parent, List<Dependency> dependenciesList){
        for(Iterator<Element> it = parent.elementIterator("dependency"); it.hasNext();)
        {
            Element element=it.next();
            Iterator itt = element.elementIterator();
            Dependency dependency = new Dependency();
            while(itt.hasNext()){
                Element value = (Element) itt.next();
                if(value.getName().equals("artifactId")){
                    dependency.setArtifact_id(value.getStringValue());
                }
                else if (value.getName().equals("version") && !value.getStringValue().equals("${project.version}")){
                    dependency.setVersion(value.getStringValue());
                }
                else if(value.getName().equals("groupId")){
                    dependency.setGroup_id(value.getStringValue());
                }
            }

            //若数据库有则返回数据库数据，若没有则爬下来加入数据库
            if(dependency.getArtifact_id() != null && dependency.getVersion() != null){
                String SqlArtifactId = pacDao.getArtifactId(dependency.getArtifact_id(), dependency.getGroup_id());
                if(SqlArtifactId == null){
                    System.out.println(dependency.getGroup_id());
                    String url = "https://mvnrepository.com/artifact/" + dependency.getGroup_id() + "/" + dependency.getArtifact_id();
                    getAll(url,dependency);
                    dependenciesList.add(dependency);
                    pacDao.insert(dependency);
                }
                else{
                    Dependency SqlDependency = pacDao.getSqlDependency(dependency.getArtifact_id(), dependency.getGroup_id());
                    SqlDependency.setVersion(dependency.getVersion());
                    dependenciesList.add(SqlDependency);
                }
            }
        }
        return;
    }

    public void getAll(String url, Dependency dependency){
        String tmp="";
        ArrayList<String> versionList= new ArrayList<>();
        ArrayList<Integer> usageList = new ArrayList<>();
        ArrayList<String> licenseList = new ArrayList<>();
        try {
            org.jsoup.nodes.Document document = Jsoup.connect(url).header("user-agent","Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/71.0.3578.80 Safari/537.36").get();
            Elements latestversionValue = document.getElementsByTag("td");
            Elements versionnameValue = document.select(".vbtn");
            Elements usageValue = document.getElementsByTag("td");
            Elements licenseValue = document.select(".b").select(".lic");

            //找到得到新版本号
            for(org.jsoup.nodes.Element element : latestversionValue) {
                if (element.text().matches("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}.*$")) {
                    tmp = element.text();
                    String ragex = "[^(a-zA-Z)]";
                    String stableSign = tmp.replaceAll(ragex, "");////提取版本号中的字母部分，以查看是否是稳定版本
                    if (stableSign.equals("Final")|| stableSign.equals("RELEASE") || stableSign.equals("") ) {
                        dependency.setStable_version(tmp);
                        break;
                    }
                }
            }

            //得到版本号数组
            for(org.jsoup.nodes.Element element:versionnameValue){
                org.jsoup.nodes.Document elementdoc = Jsoup.parse(element.toString());
                Elements versionName = elementdoc.select("a");
                versionList.add(versionName.text());

            }

            //得到热度数组
            for(org.jsoup.nodes.Element element : usageValue){
                if(element.text().matches("\\d{1,3}(,\\d{3})*$")){ //取使用量，对于三位分割法去掉中间的逗号
                    String rawString = element.text();
                    String removeStr = ",";
                    rawString = rawString.replace(removeStr,"");
                    usageList.add(Integer.parseInt(rawString));
                }
            }

            //得到license数组
            for(org.jsoup.nodes.Element element:licenseValue){
                org.jsoup.nodes.Document elementdoc = Jsoup.parse(element.toString());
                Elements license = elementdoc.select("span");
                licenseList.add(license.text());
            }


        }
        catch (IOException e){
            e.printStackTrace();
        }
        dependency.setVersionList(versionList);
        dependency.setUsageList(usageList);
        String license = String.join("  ",licenseList);
        // dependency.setLicenseList(licenseList);
        dependency.setLicense(license);
        String bestVersion = versionList.get(usageList.indexOf(Collections.max(usageList)));
        dependency.setPopular_version(bestVersion);
    }

    public static InputStream JarRead(String filepath) throws IOException{
        InputStream input = new InputStream() { //创造一个input实例，内容不重要
            @Override
            public int read() {
                return -1;  // end of stream
            }
        };
        JarFile jarFile = new JarFile(filepath);
        Enumeration enu = jarFile.entries();
        while(enu.hasMoreElements()){
            String pompath = "";
            JarEntry element = (JarEntry) enu.nextElement();
            String name = element.getName();  //获取Jar包中的条目名字
            Pattern r = Pattern.compile("(pom.xml)$");
            Matcher m = r.matcher(name);
            if(m.find()){
                pompath = name;
                JarEntry entry = jarFile.getJarEntry(pompath);
                input = jarFile.getInputStream(entry); //获取该文件的输入流
            }
        }
        return input;
    }

    public void SetNoPomDependencies(List<Dependency> dependenciesList, String fileName){
        Dependency dependency = new Dependency();
        dependency.setArtifact_id("");
        dependency.setVersion("");
        dependency.setGroup_id(fileName+"没有检测到pom.xml文件，请扫描其他Jar包");
        ArrayList<String> versionList= new ArrayList<>();
        ArrayList<Integer> usageList = new ArrayList<>();
        versionList.add("");
        usageList.add(null);
        String license = "";
        dependency.setVersionList(versionList);
        dependency.setUsageList(usageList);
        dependency.setPopular_version("");
        dependency.setStable_version("");
        dependency.setLicense(license);
        dependenciesList.add(dependency);
    }

}
