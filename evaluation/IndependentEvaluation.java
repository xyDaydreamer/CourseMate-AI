import com.google.gson.*;
import edu.t07.coursemate.ai.*;
import edu.t07.coursemate.chat.*;
import edu.t07.coursemate.rag.*;
import edu.t07.coursemate.ReplyLanguage;
import java.nio.file.*;
import java.util.*;

/** Frozen-query assessment only. Fake answers deliberately carry no correctness score. */
public class IndependentEvaluation {
    public static void main(String[] args)throws Exception {
        Path scratch=Files.createTempDirectory("cm-holdout-");
        var kb=new CourseKnowledgeBase(Path.of(args[0]),scratch.resolve("index.json"),new PdfCourseReader());
        kb.refresh(false);
        var service=RagAnswerService.structured(kb,(q,s)->ModelResponse.complete(s.isEmpty()?"Offline fixture; not a quality-rated answer.":"{\"course_answer\":[{\"text\":\"Offline fixture; not a quality-rated answer.\",\"source_ids\":[\"S1\"]}],\"general_answer\":\"\",\"source_ids\":[\"S1\"]}"));
        JsonArray cases=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonArray(),results=new JsonArray();
        int positives=0,hits=0,negatives=0,empty=0,contracts=0,passed=0;double rr=0,precision=0;
        for(var element:cases){JsonObject item=element.getAsJsonObject(),row=item.deepCopy();long start=System.nanoTime();
            if(!item.has("kind")){
                String q=item.get("question").getAsString(),code=item.has("code")?item.get("code").getAsString():"";
                var request=new ChatSession().prepare(q,ChatSession.Code.of(code),true,ReplyLanguage.EN);var answer=service.ask(request);
                List<String> pages=answer.retrieval().evidence().stream().map(c->c.fileName()+":"+c.page()).distinct().limit(3).toList();
                Set<String> expected=new LinkedHashSet<>();item.getAsJsonArray("expected").forEach(e->expected.add(e.getAsString()));
                boolean pass;
                if(expected.isEmpty()){negatives++;pass=pages.isEmpty();if(pass)empty++;}
                else{positives++;int rank=-1,matched=0;for(int i=0;i<pages.size();i++)if(expected.contains(pages.get(i))){matched++;if(rank<0)rank=i+1;}
                    pass=rank>0;if(pass){hits++;rr+=1.0/rank;}precision+=pages.isEmpty()?0:(double)matched/pages.size();
                    row.addProperty("labelledPrecisionAt3LowerBound",pages.isEmpty()?0:(double)matched/pages.size());
                    row.addProperty("acceptableLabelCoverage",(double)matched/expected.size());
                }
                row.add("actualPages",new Gson().toJsonTree(pages));row.addProperty("retrievalStatus",answer.retrieval().status().name());row.addProperty("pass",pass);
            }else{
                contracts++;boolean pass=true;String kind=item.get("kind").getAsString();
                if(kind.equals("session")){
                    ChatSession session=new ChatSession();var first=session.prepare(item.get("topic").getAsString(),null,true,ReplyLanguage.EN);session.commit(first,"Existing answer.");
                    for(var f:item.getAsJsonArray("followups")){var request=session.prepare(f.getAsString(),null,true,ReplyLanguage.EN);service.ask(request);session.commit(request,"Existing answer.");}
                    pass=session.topic().equals(item.get("expectedTopic").getAsString());row.addProperty("resolvedTopic",session.topic());
                }else switch(item.get("scenario").getAsString()){
                    case "truncated_json"->{var response=new ModelResponse("{", "length",null,"");var s=RagAnswerService.structured(kb,(q,e)->response);pass=s.ask(new ChatSession().prepare("inheritance",null,true,ReplyLanguage.EN)).completion()==AnswerResult.Completion.TRUNCATED;}
                    case "invalid_source_id"->{try{RagAnswerService.renderGrounded("{\"course_answer\":\"Claim\",\"general_answer\":\"\",\"source_ids\":[\"S9\"]}",List.of(new CourseChunk("Lec-2-Test.pdf",1,"Inheritance.")));pass=false;}catch(DeepSeekException expected){}}
                    case "cancel_then_new_session"->{var session=new ChatSession();var old=session.prepare("inheritance",null,true,ReplyLanguage.EN);session.clear();var next=session.prepare("generics",null,true,ReplyLanguage.EN);pass=!session.commit(old,"late")&&session.commit(next,"fresh");}
                    case "ordinary_pdf_filename"->{pass=RagAnswerService.renderGrounded("{\"course_answer\":\"Open notes.pdf.\",\"general_answer\":\"\",\"source_ids\":[\"S1\"]}",List.of(new CourseChunk("Lec-2-Test.pdf",1,"Files."))).contains("notes.pdf");}
                    default->pass=false;
                }
                if(pass)passed++;row.addProperty("pass",pass);
            }
            row.addProperty("elapsedMsOffline",(System.nanoTime()-start)/1e6);row.addProperty("realAnswerCorrectness","NOT_RUN");row.addProperty("claimEvidenceConsistency","NOT_RUN");results.add(row);
        }
        JsonObject out=new JsonObject();out.addProperty("mode","OFFLINE_FROZEN_QUERIES_NOT_INDEPENDENT_HUMAN_BLIND_TEST");out.addProperty("positiveQuestions",positives);out.addProperty("hitAt3Count",hits);out.addProperty("negativeQuestions",negatives);out.addProperty("correctlyEmpty",empty);out.addProperty("offlineContracts",contracts);out.addProperty("contractsPassed",passed);out.addProperty("meanReciprocalRank",rr/positives);out.addProperty("meanLabelledPrecisionAt3LowerBound",precision/positives);out.addProperty("realApiCalls",0);out.add("results",results);
        Files.writeString(Path.of(args[2]),new GsonBuilder().setPrettyPrinting().create().toJson(out));System.out.println("Frozen set: Hit@3 "+hits+"/"+positives+"; no-source "+empty+"/"+negatives+"; state/protocol "+passed+"/"+contracts);
        try(var paths=Files.walk(scratch)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
    }
}
