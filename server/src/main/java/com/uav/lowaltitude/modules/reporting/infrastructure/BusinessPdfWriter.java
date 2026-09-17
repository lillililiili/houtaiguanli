package com.uav.lowaltitude.modules.reporting.infrastructure;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import com.uav.lowaltitude.modules.reporting.application.BusinessReportingService.ExportData;
import com.uav.lowaltitude.modules.reporting.application.ReportLabels;
import com.uav.lowaltitude.platform.report.BusinessReportSource.*;

/** A4, searchable text, embedded Chinese font and vector charts; no remote/browser resources. */
@Component
public class BusinessPdfWriter {
    public byte[] write(ExportData data) {
        try (PDDocument document = new PDDocument(); var fontStream =
                new ClassPathResource("fonts/NotoSansSC-Regular.ttf").getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.getDocumentInformation().setTitle(data.preview().title() + " - " + data.preview().periodLabel());
            document.getDocumentInformation().setAuthor("低空安全管理平台");
            PDType0Font font = PDType0Font.load(document, fontStream, true);
            try (Layout layout = new Layout(document,font,data.preview().title())) {
                var p=data.preview();
                layout.paragraph(p.title() + "报表", 23, Color.decode("#123455"));
                layout.paragraph(p.periodLabel(),14,Color.decode("#1677ff"));
                layout.paragraph(p.from()+" 至 "+p.to()+"  |  生成时间："+ReportLabels.time(p.generatedAt()),10,Color.DARK_GRAY);
                layout.paragraph("数据来源："+ReportLabels.text(p.sourceMode()),10,Color.DARK_GRAY);
                if(p.simulated()) layout.paragraph("注意：包含模拟或回放数据，不可作为现场正式报表。",11,Color.decode("#b45309"));
                layout.paragraph(p.statusNote(),10,Color.GRAY);
                layout.heading("指标摘要");
                for(var s:p.sections()) layout.paragraph(s.title()+"："+(s.accessible()?s.total():"无权限")
                        +(s.snapshot()?"（当前快照）":""),12,Color.decode("#123455"));
                for(var s:p.sections()) {
                    if(!s.accessible()) continue;
                    layout.heading(s.title());
                    layout.paragraph("统计口径："+s.basis(),10,Color.GRAY);
                    if(!s.days().isEmpty()) layout.trend(s.days());
                    for(var d:s.distributions()) {
                        if(d.items().isEmpty()) continue;
                        layout.space(Math.min(250, 35 + d.items().size()*25));
                        layout.paragraph(d.title(),12,Color.decode("#123455"));
                        layout.bars(s.key(),d.key(),d.items());
                    }
                    var detail=data.details().get(s.key());
                    if(detail!=null) {
                        layout.heading(s.title()+"明细");
                        layout.paragraph("共 "+detail.total()+" 条，本 PDF 展示最近 "+detail.items().size()
                                +" 条；完整明细请导出 Excel。",10,Color.GRAY);
                        var columns=ReportLabels.columns(s.key());
                        List<String> notes = new ArrayList<>();
                        List<List<String>> cells = new ArrayList<>();
                        for (var row : detail.items()) {
                            List<String> values = new ArrayList<>();
                            for (var column : columns) {
                                String value = ReportLabels.cell(s.key(),row,column.field());
                                if (value.length()>160) {
                                    notes.add(column.label()+"（"+ReportLabels.cell(s.key(),row,"label")+"）："+value);
                                    value = "见附注 "+notes.size();
                                }
                                values.add(value);
                            }
                            cells.add(values);
                        }
                        layout.table(columns.stream().map(ReportLabels.Column::label).toList(), cells);
                        for (int i=0;i<notes.size();i++) {
                            layout.heading("附注 "+(i+1));
                            layout.paragraph(notes.get(i),10,Color.DARK_GRAY);
                        }
                    }
                }
                layout.finish();
            }
            document.save(out); return out.toByteArray();
        } catch(IOException ex) { throw new IllegalStateException("PDF 报表生成失败",ex); }
    }
    private static final class Layout implements AutoCloseable {
        private static final float LEFT=40, WIDTH=515, BOTTOM=48;
        private final PDDocument doc; private final PDType0Font font; private final String title;
        private PDPageContentStream stream; private float y;
        Layout(PDDocument doc,PDType0Font font,String title) throws IOException {
            this.doc=doc;this.font=font;this.title=title;page();
        }
        private void page() throws IOException {
            if(stream!=null)stream.close();
            PDPage page=new PDPage(PDRectangle.A4);doc.addPage(page);stream=new PDPageContentStream(doc,page);
            y=790; text(title+" · 低空安全管理平台",LEFT,815,9,Color.GRAY);
        }
        private void space(float height) throws IOException { if(y-height<BOTTOM)page(); }
        private String safe(String value) throws IOException {
            StringBuilder out=new StringBuilder();
            for(int cp:value.codePoints().toArray()) {
                if(cp=='\n') {out.append('\n');continue;}
                if(Character.isISOControl(cp)) {out.append(' ');continue;}
                String ch=new String(Character.toChars(cp));
                try {font.encode(ch);out.append(ch);} catch(IllegalArgumentException ex) {out.append('?');}
            }
            return out.toString();
        }
        private void text(String value,float x,float baseline,float size,Color color) throws IOException {
            stream.beginText();stream.setFont(font,size);stream.setNonStrokingColor(color);
            stream.newLineAtOffset(x,baseline);stream.showText(safe(value).replace('\n',' '));stream.endText();
        }
        private List<String> wrap(String value,float size,float width) throws IOException {
            List<String> result=new ArrayList<>();StringBuilder line=new StringBuilder();float current=0;
            for(int cp:safe(value).codePoints().toArray()) {
                if(cp=='\n') {result.add(line.toString());line.setLength(0);current=0;continue;}
                String ch=new String(Character.toChars(cp));float w=font.getStringWidth(ch)*size/1000;
                if(current+w>width && !line.isEmpty()){result.add(line.toString());line.setLength(0);current=0;}
                line.append(ch);current+=w;
            }
            result.add(line.toString());return result;
        }
        void paragraph(String value,float size,Color color) throws IOException {
            for(String line:wrap(value,size,WIDTH)) {space(size+7);text(line,LEFT,y-size,size,color);y-=size+7;} y-=5;
        }
        void heading(String value) throws IOException {
            space(80);y-=12;stream.setNonStrokingColor(Color.decode("#1677ff"));stream.addRect(LEFT,y-20,3,20);stream.fill();
            text(value,LEFT+12,y-16,15,Color.decode("#123455"));y-=35;
        }
        void bars(String section,String field,List<Count> rows) throws IOException {
            long max=rows.stream().mapToLong(Count::value).max().orElse(1);max=Math.max(1,max);
            for(Count row:rows) {
                var lines=wrap(ReportLabels.text(section,field,row.name()),9,145);
                float height=Math.max(24,lines.size()*13+8);space(height);
                for(int i=0;i<lines.size();i++)text(lines.get(i),LEFT,y-12-i*13,9,Color.DARK_GRAY);
                stream.setNonStrokingColor(Color.decode("#dceaff"));stream.addRect(LEFT+155,y-17,290,12);stream.fill();
                stream.setNonStrokingColor(Color.decode("#1677ff"));stream.addRect(LEFT+155,y-17,290f*row.value()/max,12);stream.fill();
                text(Long.toString(row.value()),LEFT+455,y-15,9,Color.DARK_GRAY);y-=height;
            } y-=8;
        }
        void trend(List<Day> days) throws IOException {
            space(185);text("每日趋势（数量）",LEFT,y-10,11,Color.DARK_GRAY);y-=25;
            float top=y,base=y-105,x=LEFT+25,w=WIDTH-35;long max=Math.max(1,days.stream().mapToLong(Day::value).max().orElse(1));
            stream.setStrokingColor(Color.LIGHT_GRAY);stream.setLineWidth(.5f);
            for(int tick=0;tick<=2;tick++) {
                float gy=base+tick*52.5f;stream.moveTo(x,gy);stream.lineTo(x+w,gy);stream.stroke();
                text(Long.toString(Math.round(max*tick/2.0)),LEFT,gy-3,8,Color.GRAY);
            }
            stream.setStrokingColor(Color.decode("#1677ff"));stream.setLineWidth(1.5f);
            for(int i=0;i<days.size();i++) {
                float px=x+(days.size()==1?w/2:w*i/(days.size()-1)),py=base+105f*days.get(i).value()/max;
                if(i==0)stream.moveTo(px,py);else stream.lineTo(px,py);
            }stream.stroke();
            for(int i=0;i<days.size();i++) {
                float px=x+(days.size()==1?w/2:w*i/(days.size()-1)),py=base+105f*days.get(i).value()/max;
                stream.setNonStrokingColor(Color.decode("#1677ff"));stream.addRect(px-2,py-2,4,4);stream.fill();
                if(i==0||i==days.size()-1||i==days.size()/2)text(days.get(i).date().substring(5),px-14,base-18,8,Color.GRAY);
            } y=top-142;
        }
        void table(List<String> headers,List<List<String>> rows) throws IOException {
            float width=WIDTH/headers.size();tableRow(headers,width);
            for(var row:rows) {
                List<List<String>> lines=new ArrayList<>();int count=1;
                for(String value:row){var l=wrap(value,8,width-8);lines.add(l);count=Math.max(count,l.size());}
                int start=0;
                while(start<count) {
                    int available=(int)((y-BOTTOM-10)/12);
                    if(available<2){page();tableRow(headers,width);continue;}
                    int end=Math.min(count,start+available);float height=(end-start)*12+10;
                    stream.setNonStrokingColor(Color.decode("#f3f7fc"));stream.addRect(LEFT,y-height,WIDTH,height);stream.fill();
                    for(int c=0;c<lines.size();c++)for(int k=start;k<Math.min(end,lines.get(c).size());k++)
                        text(lines.get(c).get(k),LEFT+c*width+4,y-12-(k-start)*12,8,Color.DARK_GRAY);
                    y-=height;start=end;
                }
            }
            if(rows.isEmpty())paragraph("当前周期暂无明细记录。",10,Color.GRAY);
        }
        private void tableRow(List<String> row,float width) throws IOException {
            List<List<String>> lines=new ArrayList<>();int count=1;
            for(String value:row){var l=wrap(value,8,width-8);lines.add(l);count=Math.max(count,l.size());}
            float height=count*12+10;space(height);
            stream.setNonStrokingColor(Color.decode("#123455"));stream.addRect(LEFT,y-height,WIDTH,height);stream.fill();
            for(int c=0;c<lines.size();c++)for(int i=0;i<lines.get(c).size();i++)
                text(lines.get(c).get(i),LEFT+c*width+4,y-12-i*12,8,Color.WHITE);
            y-=height;
        }
        void finish() throws IOException {
            stream.close();stream=null;
            for(int i=0;i<doc.getNumberOfPages();i++) {
                try(var footer=new PDPageContentStream(doc,doc.getPage(i),PDPageContentStream.AppendMode.APPEND,true)) {
                    footer.beginText();footer.setFont(font,9);footer.setNonStrokingColor(Color.GRAY);
                    footer.newLineAtOffset(LEFT,25);footer.showText("第 "+(i+1)+" / "+doc.getNumberOfPages()+" 页");footer.endText();
                }
            }
        }
        @Override public void close() throws IOException {if(stream!=null)stream.close();}
    }
}
