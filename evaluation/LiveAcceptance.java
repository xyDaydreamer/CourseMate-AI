import com.google.gson.*;
import edu.t07.coursemate.ai.*;
import edu.t07.coursemate.chat.*;
import edu.t07.coursemate.rag.*;
import edu.t07.coursemate.ReplyLanguage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Explicit live checks, at most six HTTP calls including service retries. Never persists credentials. */
public class LiveAcceptance {
    public static void main(String[] args)throws Exception{
        if(System.getenv("DEEPSEEK_API_KEY")==null||System.getenv("DEEPSEEK_API_KEY").isBlank())throw new IllegalStateException("Configure DEEPSEEK_API_KEY with the secure local runner; never paste it into chat.");
        Path scratch=Files.createTempDirectory("cm-live-");var kb=new CourseKnowledgeBase(Path.of(args[0]),scratch.resolve("i.json"),new PdfCourseReader());
        var client=new DeepSeekClient();AtomicInteger calls=new AtomicInteger();
        JsonArray attempts=new JsonArray();
        var service=RagAnswerService.structured(kb,(q,s)->{
            if(calls.get()>=6)throw new DeepSeekException(DeepSeekException.Kind.INPUT_LIMIT,"Live call budget exhausted",0,null);
            int number=calls.incrementAndGet();System.out.println("HTTP call "+number+"/6 started.");
            JsonObject attempt=new JsonObject();attempt.addProperty("call",number);attempts.add(attempt);
            try{var response=client.askResponse(q,s);attempt.addProperty("finishReason",response.finishReason());
                attempt.addProperty("content",response.content());attempt.add("usage",new Gson().toJsonTree(response.usage()));return response;
            }catch(DeepSeekException e){attempt.addProperty("errorKind",e.kind().name());attempt.addProperty("httpStatus",e.status());throw e;}
        });
        String[] questions={"How do generics improve type safety and remove casts?","Could you explain this code?","Give another example.","Explain photon polarization measurements.","Explain the selected fragment. State what cannot be known without the method body."};
        String[] code={"","Map<String,Integer> counts=new HashMap<>(); counts.merge(\"java\",1,Integer::sum);","","","public static void main(String[] args) { System.out.println(countWords(\"Java maps Java collections\"));"};
        if(args.length>2){var cases=JsonParser.parseString(Files.readString(Path.of(args[2]))).getAsJsonArray();
            questions=new String[cases.size()];code=new String[cases.size()];
            for(int i=0;i<cases.size();i++){var item=cases.get(i).getAsJsonObject();questions[i]=item.get("question").getAsString();code[i]=item.has("code")?item.get("code").getAsString():"";}}
        ChatSession session=new ChatSession();JsonArray rows=new JsonArray();AnswerResult toTranslate=null;
        for(int i=0;i<questions.length;i++){
            if(calls.get()>=6)break;
            JsonObject row=new JsonObject();row.addProperty("case",i+1);row.addProperty("question",questions[i]);long start=System.nanoTime();
            try{
                var language=questions[i].matches("(?s).*[\\p{IsHan}].*")?ReplyLanguage.ZH:ReplyLanguage.EN;
                var request=session.prepare(questions[i],ChatSession.Code.of(code[i]),true,language);
                row.addProperty("resolvedQuestion",request.resolvedQuestion());row.addProperty("retrievalQuery",request.retrievalQuery());
                row.addProperty("contextRelation",request.relation().name());row.addProperty("task",request.task().name());row.addProperty("historicalCode",request.historicalCode());
                var result=service.ask(request);
                row.addProperty("answer",result.prose());row.addProperty("completion",result.completion().name());row.addProperty("retrievalStatus",result.retrieval().status().name());
                row.add("claims",new Gson().toJsonTree(result.claims()));row.add("sources",new Gson().toJsonTree(result.sources()));row.add("usage",new Gson().toJsonTree(result.model().usage()));
                if(result.completion()==AnswerResult.Completion.COMPLETE)session.commit(request,result.prose());else session.cancel();
                if(i==1&&args.length<=2)toTranslate=result;
            }catch(DeepSeekException e){row.addProperty("errorKind",e.kind().name());row.addProperty("httpStatus",e.status());session.cancel();}
            row.addProperty("elapsedMs",(System.nanoTime()-start)/1e6);row.addProperty("answerCorrectness","NOT_REVIEWED");row.addProperty("claimEvidenceConsistency","NOT_REVIEWED");rows.add(row);
            System.out.println("Case "+(i+1)+" recorded: "+(row.has("errorKind")?row.get("errorKind").getAsString():row.get("completion").getAsString()));
            // A global configuration/transport failure should not consume the rest of the paid-call budget.
            if(row.has("errorKind"))break;
        }
        if(toTranslate!=null&&toTranslate.completion()==AnswerResult.Completion.COMPLETE&&calls.get()<6){
            JsonObject row=new JsonObject();row.addProperty("case","translation");
            try{var translator=new AnswerTranslation(json->{if(calls.get()>=6)throw new DeepSeekException(DeepSeekException.Kind.INPUT_LIMIT,"Live call budget exhausted",0,null);calls.incrementAndGet();return client.translateResponse(json);});row.add("translatedSegments",new Gson().toJsonTree(translator.translate(toTranslate,ReplyLanguage.ZH)));}
            catch(DeepSeekException e){row.addProperty("errorKind",e.kind().name());}
            row.addProperty("humanReview","NOT_REVIEWED");rows.add(row);
        }
        JsonObject out=new JsonObject();out.addProperty("model",DeepSeekClient.MODEL);out.addProperty("actualApiCalls",calls.get());out.addProperty("maxCalls",6);out.add("results",rows);out.add("attempts",attempts);
        Path output=Path.of(args[1]).toAbsolutePath();Files.createDirectories(output.getParent());Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(out));
        System.out.println("Live calls: "+calls.get()+"/6. Saved answers for human review; no correctness score assigned automatically.");
        try(var paths=Files.walk(scratch)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
    }
}
