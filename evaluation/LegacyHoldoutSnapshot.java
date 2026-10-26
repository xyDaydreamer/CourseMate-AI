import com.google.gson.*;
import edu.t07.coursemate.rag.*;
import java.nio.file.*;
import java.util.*;

/** Baseline retrieval from the pre-change plugin; no model API and no new-type dependencies. */
public class LegacyHoldoutSnapshot {
    public static void main(String[] args)throws Exception{
        Path scratch=Files.createTempDirectory("cm-before-");var kb=new CourseKnowledgeBase(Path.of(args[0]),scratch.resolve("i.json"),new PdfCourseReader());kb.refresh(false);
        JsonArray out=new JsonArray();for(var e:JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonArray()){
            JsonObject item=e.getAsJsonObject();if(item.has("kind"))continue;List<CourseChunk> supplied=new ArrayList<>();
            var service=new RagAnswerService(kb,(q,s)->{supplied.addAll(s);return s.isEmpty()?"Offline fixture":"{\"course_answer\":\"Offline fixture\",\"general_answer\":\"\",\"source_ids\":[\"S1\"]}";});
            long start=System.nanoTime();service.answer(item.get("question").getAsString(),item.has("code")?item.get("code").getAsString():"");
            JsonObject row=item.deepCopy();row.add("actualPages",new Gson().toJsonTree(supplied.stream().map(c->c.fileName()+":"+c.page()).distinct().limit(3).toList()));row.addProperty("elapsedMsOffline",(System.nanoTime()-start)/1e6);out.add(row);
        }Files.writeString(Path.of(args[2]),new GsonBuilder().setPrettyPrinting().create().toJson(out));
        try(var paths=Files.walk(scratch)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
    }
}
