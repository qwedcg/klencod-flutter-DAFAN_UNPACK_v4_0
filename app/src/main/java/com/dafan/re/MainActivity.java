package com.dafan.re;

import android.app.Activity;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.provider.DocumentsContract;
import android.os.ParcelFileDescriptor;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

public class MainActivity extends Activity {
    private static final int PICK_FILE = 701, PICK_OUTPUT = 702;
    private final int bg=0xFF080D18, panel=0xFF121D30, accent=0xFF35D6C5,
            white=0xFFEAF2FF, muted=0xFF91A3BD;
    private LinearLayout root, content;
    private TextView selectedLabel, status;
    private Uri sourceUri, outputTree;
    private String sourceName="لم يتم اختيار ملف";
    private long sourceSize=-1;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private volatile boolean busy=false;
    private String editedLuaSource=null;
    private String editedLuaName="edited.lua";

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Python.isStarted()) Python.start(new AndroidPlatform(this));
        buildUi();
    }

    private TextView txt(String s,int size,int color,boolean bold) {
        TextView t=new TextView(this); t.setText(s); t.setTextSize(size);
        t.setTextColor(color); t.setPadding(5,6,5,6);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return t;
    }
    private android.graphics.drawable.GradientDrawable shape(int c,int r) {
        android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();
        d.setColor(c); d.setCornerRadius(r); return d;
    }
    private TextView btn(String s,Runnable run) {
        TextView b=txt(s,14,white,true); b.setGravity(Gravity.CENTER);
        b.setPadding(12,14,12,14); b.setBackground(shape(panel,16));
        b.setOnClickListener(v->{if(!busy)run.run();}); return b;
    }
    private void buildUi() {
        root=new LinearLayout(this); root.setOrientation(1); root.setPadding(15,16,15,10);
        root.setBackgroundColor(bg); setContentView(root);
        root.addView(txt("DAFAN-UNPACK",27,accent,true));
        root.addView(txt("LUA BYTECODE UNPACKER • OFFLINE ENGINE",10,muted,true));
        LinearLayout row=new LinearLayout(this);
        TextView open=btn("＋ فتح ملف",()->pick(PICK_FILE));
        TextView out=btn("اختيار مجلد الإخراج",()->pick(PICK_OUTPUT));
        row.addView(open,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.leftMargin=8;
        row.addView(out,p);root.addView(row);
        selectedLabel=txt(sourceName,13,white,true); selectedLabel.setBackground(shape(panel,13));
        root.addView(selectedLabel);
        status=txt("اختر ملفاً ثم اختر مجلد الإخراج لبدء الاستخراج.",12,muted,false);
        root.addView(status);
        ScrollView scroll=new ScrollView(this);
        content=new LinearLayout(this);content.setOrientation(1);scroll.addView(content);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        root.addView(txt("DAFAN • معالجة محلية • لا يتم رفع ملفاتك",10,muted,false));
        welcome();
    }
    private void welcome() {
        content.removeAllViews();
        card("DAFAN Lua Engine", "فك وتجميع Lua المخصص يتمان داخل التطبيق عبر محرك Python مضمّن وأدوات Lua أصلية ARM64. اختر ملف Bytecode ثم نفّذ Decompile. بعد ذلك اختر ملف المصدر الناتج وعدّله ثم نفّذ Compile.\n\nELF / SO: قراءة ترويسة ELF واستخراج الأقسام الفعلية مثل .text و .rodata و .data.\n\nZIP / APK / JAR / OBB: استخراج عناصر الأرشيف واحداً تلو الآخر.\n\nملفات كبيرة: نسخ متدفق بذاكرة مؤقتة صغيرة، دون تحميل الملف كاملاً إلى RAM.");
        card("الصيغ المعروفة", "SO • ELF • EXE • DLL • DEX • APK • ZIP • JAR • OBB • PAK (تعرف مبدئي فقط)");
        card("مهم", "هذا الإصدار لا يدّعي فك تشفير PAK أو Unreal IoStore أو كل ملفات الألعاب. استخراج UASSET/UEXP من حاوية يتطلب محلل الحاوية وإصدار اللعبة ومفاتيحها عند وجود تشفير.");
    }
    private void card(String h,String body) {
        LinearLayout c=new LinearLayout(this);c.setOrientation(1);c.setPadding(13,11,13,11);
        c.setBackground(shape(panel,16));c.addView(txt(h,16,accent,true));c.addView(txt(body,13,white,false));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,7,0,7);content.addView(c,lp);
    }
    private void pick(int request) {
        Intent i=new Intent(request==PICK_FILE?Intent.ACTION_OPEN_DOCUMENT:Intent.ACTION_OPEN_DOCUMENT_TREE);
        if(request==PICK_FILE){i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");}
        startActivityForResult(i,request);
    }
    @Override protected void onActivityResult(int r,int result,Intent d) {
        super.onActivityResult(r,result,d);
        if(result!=RESULT_OK||d==null)return;
        Uri u=d.getData(); if(u==null)return;
        if(r==PICK_FILE){
            sourceUri=u;
            try{getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
            sourceName=queryName(u);sourceSize=querySize(u);
            selectedLabel.setText(sourceName+"  •  "+human(sourceSize));
            status.setText("تم اختيار الملف. اختر مجلد الإخراج ثم نفّذ العملية المناسبة.");
            showActions();
        } else if(r==PICK_OUTPUT){
            outputTree=u;
            try{getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);}catch(Exception ignored){}
            status.setText("تم تحديد مجلد الإخراج. يمكنك الآن استخراج الملف.");
            showActions();
        }
    }
    private String queryName(Uri u) {
        String n="input.bin";Cursor c=getContentResolver().query(u,null,null,null,null);
        if(c!=null){try{if(c.moveToFirst()){int x=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(x>=0)n=c.getString(x);}}finally{c.close();}}
        return n;
    }
    private long querySize(Uri u) {
        Cursor c=getContentResolver().query(u,null,null,null,null);
        if(c!=null){try{if(c.moveToFirst()){int x=c.getColumnIndex(OpenableColumns.SIZE);if(x>=0&&!c.isNull(x))return c.getLong(x);}}finally{c.close();}}
        return -1;
    }
    private String human(long n) {
        if(n<0)return "الحجم غير معروف";
        if(n>=1024L*1024*1024)return String.format(Locale.US,"%.2f GB",n/1073741824.0);
        if(n>=1024*1024)return String.format(Locale.US,"%.2f MB",n/1048576.0);
        return n+" bytes";
    }
    private void showActions() {
        content.removeAllViews();
        card("الملف المحدد", "الاسم: "+sourceName+"\nالحجم: "+human(sourceSize)+"\nالمسار: "+(sourceUri==null?"—":"تم فتحه عبر مدير ملفات أندرويد")+"\nمجلد الإخراج: "+(outputTree==null?"لم يُحدد":"جاهز"));
        addAction("تحليل الترويسة",()->runTask(()->analyze()));
        addAction("استخراج أقسام ELF / SO",()->runTask(()->extractElf()));
        addAction("استخراج ZIP / APK / JAR / OBB",()->runTask(()->extractZip()));
        addAction("استخراج النصوص ASCII",()->runTask(()->extractStrings()));
        addAction("تحليل ملف Lua / Bytecode",()->runTask(()->analyzeLua()));
        addAction("فك تشفير / Decompile Lua",()->runTask(()->decompileLuaInApp()));
        addAction("فتح محرر Lua",()->runTask(()->openLuaEditor()));
        addAction("إعادة تجميع / Compile Lua",()->runTask(()->compileLuaInApp()));
        addAction("استخراج موارد مضمّنة معروفة",()->runTask(()->carveKnown()));
        card("تنبيه", "استخراج أقسام ELF يحافظ على أسماء الأقسام ومحتواها. أما الموارد المضمّنة فيتم التعرف عليها بالتواقيع المعروفة وقد لا يكتشف كل تنسيق خاص.");
    }
    private void addAction(String s,Runnable r) {
        TextView b=btn(s,r);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,5,0,5);content.addView(b,p);
    }
    private void runTask(Runnable r) {
        if(sourceUri==null){say("اختر ملفاً أولاً");return;}
        if(outputTree==null){say("اختر مجلد الإخراج أولاً");return;}
        busy=true;status.setText("جارٍ العمل...");
        worker.execute(()->{try{r.run();}catch(Throwable e){say("فشلت العملية: "+e.getMessage());}finally{busy=false;}});
    }
    private void say(String s){runOnUiThread(()->status.setText(s));}
    private void requireOutput() throws IOException {if(outputTree==null)throw new IOException("لم يتم اختيار مجلد الإخراج");}
    private Uri createOut(String name,String mime) throws IOException {
        requireOutput();
        String safe=safeName(name);
        Uri doc=DocumentsContract.buildDocumentUriUsingTree(outputTree,DocumentsContract.getTreeDocumentId(outputTree));
        Uri made=DocumentsContract.createDocument(getContentResolver(),doc,mime,safe);
        if(made==null)throw new IOException("تعذر إنشاء "+safe);
        return made;
    }
    private String safeName(String s) {
        String x=s.replaceAll("[\\\\/:*?\"<>|]","_").replace("..","_");
        if(x.trim().isEmpty())x="unnamed.bin";return x;
    }
    private OutputStream out(String name,String mime)throws IOException {
        Uri u=createOut(name,mime);OutputStream o=getContentResolver().openOutputStream(u,"w");
        if(o==null)throw new IOException("تعذر فتح ملف الإخراج");return o;
    }
    private InputStream input()throws IOException {
        InputStream i=getContentResolver().openInputStream(sourceUri);
        if(i==null)throw new IOException("تعذر فتح الملف");return new BufferedInputStream(i,128*1024);
    }
    private void analyze() {
        try(InputStream in=input()){
            byte[] h=new byte[64];int n=in.read(h);
            String kind="Binary / غير معروف";
            if(n>=4&&h[0]==0x7f&&h[1]=='E'&&h[2]=='L'&&h[3]=='F')kind="ELF / SO";
            else if(n>=4&&h[0]=='P'&&h[1]=='K')kind="ZIP / APK";
            else if(n>=4&&h[0]=='d'&&h[1]=='e'&&h[2]=='x'&&h[3]=='\n')kind="DEX";
            else if(n>=2&&h[0]=='M'&&h[1]=='Z')kind="PE / EXE / DLL";
            else if(n>=4&&h[0]=='U'&&h[1]=='E'&&h[2]=='4')kind="Unreal-related signature (heuristic)";
            final String result="النوع: "+kind+"\nالاسم: "+sourceName+"\nالحجم: "+human(sourceSize)+"\nأول بايتات: "+hex(h,Math.max(0,n));
            say(result);
        }catch(Exception e){say("خطأ: "+e.getMessage());}
    }
    private String hex(byte[] b,int n){StringBuilder s=new StringBuilder();for(int i=0;i<n;i++)s.append(String.format(Locale.US,"%02X ",b[i]&255));return s.toString();}
    private static class Elf {
        boolean is64,little;long shoff;int shentsize,shnum,shstr;
    }
    private ByteBuffer readAt(FileChannel ch,long pos,int count)throws IOException {
        if(count<0||count>1024*1024)throw new IOException("حجم قراءة غير صالح");
        ByteBuffer b=ByteBuffer.allocate(count);int got=0;
        while(b.hasRemaining()){int n=ch.read(b,pos+got);if(n<0)break;if(n==0)continue;got+=n;}
        if(got!=count)throw new EOFException("نهاية غير متوقعة عند "+pos);
        b.flip();return b;
    }
    private Elf parseElf(FileChannel ch)throws IOException {
        ByteBuffer h=readAt(ch,0,64);byte[] m=new byte[4];h.get(m);
        if(m[0]!=0x7f||m[1]!='E'||m[2]!='L'||m[3]!='F')throw new IOException("الملف ليس ELF");
        int cls=h.get(4)&255,enc=h.get(5)&255;
        if((cls!=1&&cls!=2)||(enc!=1&&enc!=2))throw new IOException("ELF class/endian غير مدعوم");
        Elf e=new Elf();e.is64=cls==2;e.little=enc==1;
        h.order(e.little?ByteOrder.LITTLE_ENDIAN:ByteOrder.BIG_ENDIAN);
        if(e.is64){e.shoff=h.getLong(40);e.shentsize=h.getShort(58)&65535;e.shnum=h.getShort(60)&65535;e.shstr=h.getShort(62)&65535;}
        else{e.shoff=h.getInt(32)&0xffffffffL;e.shentsize=h.getShort(46)&65535;e.shnum=h.getShort(48)&65535;e.shstr=h.getShort(50)&65535;}
        if(e.shoff<0||e.shentsize<(e.is64?64:40)||e.shnum>100000)throw new IOException("جدول أقسام ELF غير صالح أو Extended numbering غير مدعوم");
        return e;
    }
    private static class Section {long name,offset,size,type;int nameIndex;}
    private Section section(FileChannel ch,Elf e,int idx)throws IOException {
        long p=e.shoff+(long)idx*e.shentsize;
        ByteBuffer b=readAt(ch,p,e.shentsize).order(e.little?ByteOrder.LITTLE_ENDIAN:ByteOrder.BIG_ENDIAN);
        Section s=new Section();s.nameIndex=b.getInt(0);
        if(e.is64){s.type=b.getInt(4)&0xffffffffL;s.offset=b.getLong(24);s.size=b.getLong(32);}
        else{s.type=b.getInt(4)&0xffffffffL;s.offset=b.getInt(16)&0xffffffffL;s.size=b.getInt(20)&0xffffffffL;}
        return s;
    }
    private String cString(byte[] b,int off) {
        if(off<0||off>=b.length)return "section_"+off;int end=off;
        while(end<b.length&&b[end]!=0)end++;
        return new String(b,off,end-off,StandardCharsets.UTF_8);
    }
    private void extractElf() {
        int count=0;long bytes=0;
        try(ParcelFileDescriptor pfd=getContentResolver().openFileDescriptor(sourceUri,"r");
            FileInputStream fis=new FileInputStream(pfd.getFileDescriptor());FileChannel ch=fis.getChannel()) {
            Elf e=parseElf(ch);
            if(e.shnum==0||e.shstr>=e.shnum)throw new IOException("لا يوجد جدول أقسام تقليدي أو يحتاج ELF extended numbering");
            Section names=section(ch,e,e.shstr);
            if(names.size>16*1024*1024)throw new IOException("جدول أسماء الأقسام أكبر من الحد الآمن");
            byte[] nameBytes=new byte[(int)names.size];ByteBuffer nb=ByteBuffer.wrap(nameBytes);
            while(nb.hasRemaining()){int n=ch.read(nb,names.offset+nb.position());if(n<0)throw new EOFException();if(n==0)continue;}
            for(int i=0;i<e.shnum;i++){
                Section s=section(ch,e,i);
                if(s.type==8||s.size==0)continue; // SHT_NOBITS has no bytes in file
                if(s.offset<0||s.size<0||s.offset>ch.size()||s.size>ch.size()-s.offset)continue;
                String nm=cString(nameBytes,s.nameIndex);
                if(nm.isEmpty())nm="section_"+i;
                String fn=String.format(Locale.US,"%03d_%s.bin",i,safeName(nm));
                try(OutputStream o=out(fn,"application/octet-stream")){
                    copyRange(ch,s.offset,s.size,o);
                }
                count++;bytes+=s.size;
                say("استخراج ELF: "+count+" أقسام • "+human(bytes)+"\nآخر قسم: "+nm);
            }
            say("اكتمل استخراج ELF / SO\nالأقسام: "+count+"\nالحجم المستخرج: "+human(bytes));
        }catch(Exception ex){say("تعذر استخراج ELF: "+ex.getMessage());}
    }
    private void copyRange(FileChannel ch,long pos,long len,OutputStream out)throws IOException {
        byte[] buf=new byte[128*1024];long done=0;
        while(done<len){int want=(int)Math.min(buf.length,len-done);ByteBuffer b=ByteBuffer.wrap(buf,0,want);
            int n=ch.read(b,pos+done);if(n<0)throw new EOFException();if(n==0)continue;
            out.write(buf,0,n);done+=n;
        }
    }
    private void extractZip() {
        int count=0;long bytes=0;
        try(InputStream raw=input();ZipInputStream zin=new ZipInputStream(raw)){
            ZipEntry e;
            while((e=zin.getNextEntry())!=null){
                if(e.isDirectory()){zin.closeEntry();continue;}
                String original=e.getName().replace('\\','/');
                String flat=original.replace("/","__");
                if(flat.length()>180)flat=flat.substring(flat.length()-180);
                try(OutputStream o=out(String.format(Locale.US,"%04d_%s",count,safeName(flat)),"application/octet-stream")){
                    byte[] buf=new byte[128*1024];int n;
                    while((n=zin.read(buf))!=-1){o.write(buf,0,n);bytes+=n;}
                }
                count++;say("استخراج الأرشيف: "+count+" ملفات • "+human(bytes)+"\n"+original);
                zin.closeEntry();
            }
            say("اكتمل استخراج الأرشيف\nالملفات: "+count+"\nالحجم: "+human(bytes));
        }catch(Exception e){say("فشل استخراج ZIP: "+e.getMessage()+"\nقد يكون الأرشيف غير ZIP أو مشفراً بطريقة غير مدعومة.");}
    }
    private void extractStrings() {
        int count=0;long scanned=0;
        try(InputStream in=input();OutputStream o=out(stem(sourceName)+"_strings.txt","text/plain")){
            StringBuilder line=new StringBuilder();byte[] buf=new byte[128*1024];int n;
            while((n=in.read(buf))!=-1){scanned+=n;
                for(int i=0;i<n;i++){int v=buf[i]&255;
                    if(v>=32&&v<=126){line.append((char)v);}
                    else {if(line.length()>=4){o.write(line.toString().getBytes(StandardCharsets.UTF_8));o.write('\n');count++;}line.setLength(0);}
                }
                say("فحص النصوص: "+human(scanned)+" • أسطر: "+count);
            }
            if(line.length()>=4){o.write(line.toString().getBytes(StandardCharsets.UTF_8));o.write('\n');count++;}
            say("اكتمل استخراج النصوص\nتم فحص: "+human(scanned)+"\nالأسطر: "+count);
        }catch(Exception e){say("خطأ Strings: "+e.getMessage());}
    }
    private String stem(String n){int p=n.lastIndexOf('.');return p>0?n.substring(0,p):n;}
    private void carveKnown() {
        // Signature carving is deliberately conservative: extract only recognizable PNG/JPEG/ZIP headers.
        int found=0;long absolute=0;byte[] buf=new byte[128*1024+16];int carry=0;
        try(InputStream in=input()){
            int n;
            while((n=in.read(buf,carry,128*1024))!=-1){
                int total=carry+n;
                for(int i=0;i<=total-4;i++){
                    String ext=null;int skip=0;
                    if((buf[i]&255)==0x89&&buf[i+1]=='P'&&buf[i+2]=='N'&&buf[i+3]=='G'){ext=".png";skip=8;}
                    else if((buf[i]&255)==0xff&&(buf[i+1]&255)==0xd8&&(buf[i+2]&255)==0xff){ext=".jpg";skip=3;}
                    else if(buf[i]=='P'&&buf[i+1]=='K'&&buf[i+2]==3&&buf[i+3]==4){ext=".zip";skip=4;}
                    if(ext!=null){
                        long start=absolute+i-carry;
                        // Keep a marker record; carving an unknown-length object is unsafe.
                        try(OutputStream o=out(String.format(Locale.US,"signature_%04d_offset_%X%s.txt",found,start,ext), "text/plain")){
                            String note="Detected signature "+ext+" at source offset 0x"+Long.toHexString(start)+
                                ". This is a detection marker only; safe extraction needs a format-specific end/length parser.";
                            o.write(note.getBytes(StandardCharsets.UTF_8));
                        }
                        found++;
                    }
                }
                absolute+=n;
                carry=Math.min(8,total);
                System.arraycopy(buf,total-carry,buf,0,carry);
                say("فحص التواقيع: "+human(absolute)+" • اكتشافات: "+found);
            }
            say("انتهى فحص التواقيع. اكتشافات: "+found+"\nتم إنشاء ملفات تقارير بالإزاحات، وليس قصّ بيانات عشوائياً.");
        }catch(Exception e){say("خطأ فحص التواقيع: "+e.getMessage());}
    }

    private File appWorkDir() throws IOException {
        File d=new File(getFilesDir(),"DAFAN-UNPACK");
        if(!d.exists()&&!d.mkdirs()) throw new IOException("تعذر إنشاء مجلد العمل الداخلي");
        File tools=new File(d,"EXES"); if(!tools.exists())tools.mkdirs();
        copyAssetIfPresent("bin/arm64-v8a/luadec",new File(tools,"luadec"));
        copyAssetIfPresent("bin/arm64-v8a/luac5.3",new File(tools,"luac5.3"));
        new File(tools,"luadec").setExecutable(true,false);
        new File(tools,"luac5.3").setExecutable(true,false);
        return d;
    }
    private void copyAssetIfPresent(String asset,File dest) {
        if(dest.exists())return;
        try(InputStream in=getAssets().open(asset);OutputStream out=new FileOutputStream(dest)) {
            byte[] b=new byte[32768];int n;while((n=in.read(b))>0)out.write(b,0,n);
        } catch(Exception ignored) { }
    }
    private File copyUriToWork(Uri uri,String name) throws IOException {
        File f=new File(appWorkDir(),safeName(name));
        try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(f)) {
            if(in==null)throw new IOException("تعذر قراءة الملف المختار");
            byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
        }
        return f;
    }
    private String callLuaEngine(String action,File in,File out,File original) throws Exception {
        Python py=Python.getInstance();
        com.chaquo.python.PyObject mod=py.getModule("DAFAN_LUA_ENGINE");
        com.chaquo.python.PyObject result=mod.callAttr("run_from_app",action,in.getAbsolutePath(),out.getAbsolutePath(),
            original==null?null:original.getAbsolutePath(),appWorkDir().getAbsolutePath());
        return result.toString();
    }
    private void decompileLuaInApp() {
        try {
            File work=appWorkDir();
            File original=copyUriToWork(sourceUri,"last_original.luac");
            File output=new File(work,"last_decompiled.lua");
            String result=callLuaEngine("decompile",original,output,null);
            if(!result.startsWith("OK|"))throw new IOException(result.replace("ERROR|",""));
            try(InputStream in=new FileInputStream(output);OutputStream o=out(stem(sourceName)+"_DECOMPILED.lua","text/x-lua")) {
                byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)o.write(b,0,n);
            }
            say("تم فك Lua فعلياً داخل التطبيق وحفظ المصدر في مجلد الإخراج.\n"+result.substring(3));
        } catch(Exception e) { say("فشل فك Lua: "+e.getMessage()+"\nتأكد من أن الملف من تنسيق الأداة وأن محرك luadec مضمّن في نسخة APK."); }
    }
    private void compileLuaInApp() {
        try {
            File work=appWorkDir();
            File original=new File(work,"last_original.luac");
            if(!original.exists())throw new IOException("فك الملف الأصلي أولاً من داخل التطبيق حتى يحتفظ التطبيق بمرجع البايت كود.");
            File source=copyUriToWork(sourceUri,"edited_source.lua");
            File snapshot=new File(work,"last_decompiled.lua.orig");
            if(snapshot.exists()) {
                try(InputStream in=new FileInputStream(snapshot);OutputStream o=new FileOutputStream(source.getAbsolutePath()+".orig")) {
                    byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)o.write(b,0,n);
                }
            }
            File output=new File(work,"rebuilt_custom.luac");
            String result=callLuaEngine("compile",source,output,original);
            if(!result.startsWith("OK|"))throw new IOException(result.replace("ERROR|",""));
            try(InputStream in=new FileInputStream(output);OutputStream o=out(stem(sourceName)+"_REBUILT.luac","application/octet-stream")) {
                byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)o.write(b,0,n);
            }
            say("اكتملت إعادة التجميع وحُفظ ملف Bytecode الجديد.\n"+result.substring(3));
        } catch(Exception e) { say("فشلت إعادة التجميع: "+e.getMessage()); }
    }

    // Lua bytecode engine is embedded through Chaquopy; native luadec/luac tools are packaged as app assets.
    private int readSome(InputStream in, byte[] b) throws IOException {
        int total=0, n;
        while(total<b.length && (n=in.read(b,total,b.length-total))>0) total+=n;
        return total;
    }
    private String readLuaSource() throws IOException {
        if(sourceSize>2L*1024*1024) throw new IOException("ملف Lua أكبر من 2MB؛ المحرر النصي الحالي يرفض تحميله كاملاً لحماية الذاكرة.");
        try(InputStream in=input(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[8192]; int n;
            while((n=in.read(b))!=-1) out.write(b,0,n);
            byte[] data=out.toByteArray();
            if(data.length>=4 && data[0]==0x1b && data[1]=='L' && data[2]=='u' && data[3]=='a')
                throw new IOException("هذا Lua Bytecode وليس نص Lua. الإصدار/الترويسة سيظهران في تحليل Lua؛ يلزم Decompiler متوافق مع نسخة Lua والـ fork المستخدم.");
            String text=new String(data,StandardCharsets.UTF_8);
            if(text.indexOf('\u0000')>=0) throw new IOException("الملف يحتوي بايتات صفرية وليس ملف Lua نصياً عادياً.");
            return text;
        }
    }
    private void analyzeLua() {
        try(InputStream in=input()) {
            byte[] h=new byte[16]; int n=readSome(in,h);
            StringBuilder result=new StringBuilder();
            result.append("Lua file inspection\n");
            result.append("Name: ").append(sourceName).append("\n");
            result.append("Size: ").append(human(sourceSize)).append("\n");
            if(n>=4 && h[0]==0x1b && h[1]=='L' && h[2]=='u' && h[3]=='a') {
                result.append("Type: Lua binary chunk / bytecode\n");
                if(n>=5) {
                    int v=h[4]&255;
                    result.append("Version byte: 0x").append(String.format(Locale.US,"%02X",v)).append("\n");
                    String ver="غير معروف";
                    if(v==0x51)ver="Lua 5.1"; else if(v==0x52)ver="Lua 5.2"; else if(v==0x53)ver="Lua 5.3"; else if(v==0x54)ver="Lua 5.4";
                    result.append("Likely version: ").append(ver).append("\n");
                }
                result.append("Result: header detected. Full decompilation is not claimed; use a decompiler matching this exact Lua version/fork.");
            } else {
                result.append("Type: probable Lua source/text (header is not definitive)\n");
                result.append("UTF-8 text editing and saving as .lua is available.");
            }
            say(result.toString());
        } catch(Exception e) { say("Lua analysis error: "+e.getMessage()); }
    }
    private void openLuaEditor() {
        try {
            String src=readLuaSource();
            editedLuaSource=src;
            editedLuaName=stem(sourceName)+"_edited.lua";
            runOnUiThread(()->{
                android.widget.EditText editor=new android.widget.EditText(this);
                editor.setText(editedLuaSource);
                editor.setTextSize(13);
                editor.setTypeface(Typeface.MONOSPACE);
                editor.setTextColor(white);
                editor.setHintTextColor(muted);
                editor.setBackgroundColor(panel);
                editor.setGravity(Gravity.TOP|Gravity.LEFT);
                editor.setMinLines(12);
                editor.setPadding(14,12,14,12);
                new android.app.AlertDialog.Builder(this)
                    .setTitle("DAFAN Lua Editor")
                    .setView(editor)
                    .setNegativeButton("إلغاء", (d,w)->{})
                    .setPositiveButton("حفظ التعديلات", (d,w)->{
                        editedLuaSource=editor.getText().toString();
                        status.setText("تم حفظ التعديلات في الذاكرة. اضغط إعادة بناء / حفظ Lua المعدّل.");
                    }).show();
            });
        } catch(Exception e) { say("تعذر فتح محرر Lua: "+e.getMessage()); }
    }
    private void rebuildLuaSource() {
        if(editedLuaSource==null) {
            say("افتح محرر Lua وعدّل النص ثم احفظ التعديلات أولاً.");
            return;
        }
        try(OutputStream out=out(editedLuaName,"text/x-lua")) {
            out.write(editedLuaSource.getBytes(StandardCharsets.UTF_8));
            out.flush();
            say("تم حفظ المصدر المعدّل: "+editedLuaName+
                "\nهذا Rebuild لملف Lua النصي. لم يتم تحويله إلى Bytecode؛ ذلك يحتاج luac مطابقاً لإصدار اللعبة.");
        } catch(Exception e) { say("فشل حفظ Lua المعدّل: "+e.getMessage()); }
    }

    @Override protected void onDestroy(){super.onDestroy();worker.shutdownNow();}
}
