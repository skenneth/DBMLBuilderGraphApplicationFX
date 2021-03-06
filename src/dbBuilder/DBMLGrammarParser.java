package dbBuilder;

import basics.graph.Edge;
import basics.graph.Graph;
import basics.graph.Vertex;
import models.shapes.Row;
import models.shapes.Table;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class DBMLGrammarParser {
    private static Graph graph;
    private static StringBuilder content;
    private static String[] keywords =
            {"Table", "Enum", "ref"};
    private static String[] types =
            {"int", "varchar"};
    private static Table table;
    private static Row childRow;
    private static String tableType, rowType;
    private static List<Edge> edges = new ArrayList<>();
    private static boolean primaryKey = false;

    private static List<String> enums = new ArrayList<>();
    private static List<Row> tableVariables = new ArrayList<>();
    private static int lineNumber = 0;

    public static void setGraph(Graph g) { graph = g; }
    private static int getEndOfWord() {
        int space = content.indexOf(" ");
        int recent = (space < 0) ? 1 : space;
        //consider if EOF
        recent = (content.length() == 0) ? 0 : recent;
        return recent;
    }
    private static String peekToken(){
        int recent = getEndOfWord();
        return content.substring(0, recent);
    }
    private static String getToken() {
        String token = peekToken();
        int recent = getEndOfWord();
        content = content.delete(0, recent+1);
        content.trimToSize();
        return token;
    }
    private static int getClosest(String str, int current) {
        int nextIndex = content.indexOf(str);
        if(nextIndex == -1)
            return current;
        else
            return Math.min(current, nextIndex);
    }
    //consume up to next valid name, }, (Table, Enum, ref, or $ (EOF))
    private static String consume(String type) {
        int closest = content.length()-1; // $ (EOF)
        closest = getClosest("Table", closest);
        closest = getClosest("Enum", closest);
        closest = getClosest("ref", closest);
        switch(type.toLowerCase()) {
            case "row":
                closest = getClosest("}", closest);
                String nextWord = nextValidName(); //find next valid word
                closest = getClosest(nextWord, closest);
                childRow = null;
                rowType = null;
                break;
            case "table":
                clear();
                break;
        }
        String str = content.substring(0, closest);
        content.delete(0, closest);
        return str;
    }
    private static String nextValidName() {
        String[] words = content.toString().split(" ");
        for(String word : words)
            if(validName(word))
                return word;
        return "$"; //return EOF
    }
    public static void build(String input){
        input = input.trim();
        String[] temp = input.split("\\s+");
        content = new StringBuilder();
        int count = 0;
        while(count < temp.length) {
            if(count > 0)
                if(!temp[count].equals(""))
                    content.append(" ");
            content.append(temp[count]);
            count++;
        }
        content.append(" $");
        parseContainer();
    }

    private static void buildFromFile(String filename) throws IOException{
        content = new StringBuilder();
        File file;
        FileReader fr = null;
        BufferedReader br = null;
        try {
            file = new File(filename);
            fr = new FileReader(file);
            br = new BufferedReader(fr);
            String[] ext = filename.trim().split(".*\\.");
            String line;
            int count;
            int loops = 0;
            String[] temp;
            while ((line = br.readLine()) != null) {
                count = 0;
                line = line.trim();
                temp = line.split("(\\s)+");
                while(count < temp.length) {
                    if(count > 0 || loops > 0)
                        if(!temp[count].equals(""))
                            content.append(" ");
                    content.append(temp[count]);
                    count++;
                }
                loops++;
            }
            content.append(" $"); //EOF indicator
        }catch(Exception ex) {
            if(fr == null)
                System.out.println("Mistyped file");
        }
    }

    public static void parseDB(String filename) {
        try {
            buildFromFile(filename);
            System.out.println("DONE");
        }catch(Exception ex) {
            System.out.println("Error opening file");
        }

        parseContainer();
    }

    public static void addTable() {
        if(table == null) return;
        //REMINDER: NOTHING HAS BEEN ADDED TO THE GRAPH OFFICIALLY
        //ADD ALL VERTICES AND EDGES

        for(Edge e : edges) {
            graph.addEdge(e);
            //TODO: REMINDER THAT THIS IS FOR ANY 'REF' ROW CONNECTIONS
        }

        String from = table.getName();
        String to;
        for(Edge e : edges) {
            to = from + " : " + e.getTo().getLabel();
            graph.addVertices(from, to);
        }
        injectContainers();
        clear();
    }

    //Add " : " to containers due to ensure unique table:row vertices are added
    private static void injectContainers() {
        String tableName = table.getName();
        Vertex t = graph.getVertex(tableName);
        t.setValue(table);

        for(Row row : tableVariables) {
            String rowName = tableName + " : " + row.getName();
            Vertex r = graph.getVertex(rowName);
            r.setValue(row);
        }
    }

    public static void clear() {
        //clear rows
        childRow = null;
        rowType = null;
        edges.clear();
        tableVariables.clear();
        //clear table
        table = null;
        tableType = null;
        primaryKey = false;
    }

    public static void addRow() {
        if(childRow == null) return;
        String header = table.getName();
        String rowName = childRow.getName();
        Vertex v1 = new Vertex(header);
        Vertex v2 = new Vertex(rowName);
        Edge tablesRow = new Edge(v1, v2, header + " -> " + rowName);
        edges.add(tablesRow);

        Row newRow = new Row(rowName);
        tableVariables.add(newRow);
    }

    //CONTAINERS -> CONTAINER | CONTAINER CONTAINERS
    //CONTAINER -> TABLE | ENUM | REF | EPSILON
    private static void parseContainer() {
        String token;
        boolean issue;
        do {
            issue = false;
            token = getToken();
            switch(token) {
                case "Table":
                    if(!parseTable()) {
                        consume("table");
                        issue = true;
                        break;
                    }
                case "Enum": parseEnum(); break;
                case "ref": parseRef(); break;
                case "$": System.out.println("End of file"); break;
                default:
                    System.out.println("Expected 'Table', 'Enum' or 'ref'");
                    consume("table");
                    issue = true;
            }
            if(!issue) {
                //TODO: USE LOGIC AND EDGE CASES AND THINK WHEN TO ADD TABLE VERTEX
                addTable();
            }
        } while(!token.equals("$"));
    }

    //TABLE -> Table NAME { TABLE_CONTENT }
    private static boolean parseTable() {
        String name = peekToken();
        if(validName(name)) {
            getToken();
            table = new Table(name);
        }
        else {
            System.out.println("Invalid or no table name");
            return false;
        }
        String token = peekToken();
        switch(token) {
            case "{": getToken(); break;
            default:
                System.out.println("Expected {");
        }

        parseTableContent();

        token = peekToken();
        switch(token) {
            case "}": getToken(); break;
            default:
                System.out.println("Expected a }");
        }
        return true;
    }

    //TABLE_CONTENT -> TABLE_ROW | TABLE_ROW TABLE_CONTENT
    private static void parseTableContent() {
        String token = peekToken();
        boolean issue;
        while(!token.equals("}") && !token.equals("")) {
            issue = false;
            //TABLE_ROW
            if(validName(token)) {
                if(!parseTableRow()) {
                    consume("row");
                }
                else {
                    childRow = new Row(token);
                    addRow(); //adds edge between table and row
                }
            }
            else if(validKeyword(token)) {
                consume("row");
                issue = true;
            }
            else {
                if(validType(token) || validEnum(token)) {
                    System.out.println("Expected a variable name");
                }
                consume("row");
                issue = true;
            }
            token = peekToken();
            if(!issue) {

            }
        }
    }

    //TABLE_ROW -> EPSILON | NAME TYPE | NAME TYPE pk | NAME ENUM_TYPE
    private static boolean parseTableRow() {
        String name = getToken();
        String token = peekToken();
        //EPSILON
        if(name.equals("}")) {
            return false;
        }
        //NAME TYPE
        else if(validType(token)) {
            getToken();
            primaryKey = false;
            rowType = token;
            String pk = peekToken();
            //NAME TYPE PK
            if(pk.toUpperCase().equals("PK")) {
                getToken();
                primaryKey = true;
                table.setPrimaryKey(true);
                if(!pk.equals("PK"))
                    System.out.println("PK misspelled");
            }
            else if(!pk.equals("}") && !validName(pk)) {
                return false;
            }
            return true;
        }
        //NAME ENUM_TYPE
        else if(validEnum(token)) {
            //TODO: add new Vertex row here for later
            rowType = token;
            return true;
        }
        else {
            if(!validName(token))
                System.out.println("models.Row needs valid variable name");
            else if(!validType(token))
                System.out.println("models.Row needs valid type");
            else if(!validType(token))
                System.out.println("models.Row needs valid enum");
            else
                System.out.println("SOME OTHER ERROR PARSING ROW");
            return false;
        }
    }

    //ENUM -> Enum ENUM_TYPE { ENUM_CONTENT }
    private static void parseEnum() {

    }

    //ENUM_CONTENT -> ENUM_ROW | ENUM_ROW ENUM_CONTENT
    private static void parseEnumContent() {

    }

    //ENUM_ROW -> NAME [ note: NAME ]
    private static void parseEnumRow() {

    }

    private static void parseRef() {

    }

    private static boolean validName(String name) {
        //TODO: use regex to validate name
        //TODO: make sure name isn't a keyword
        if(name.equals("$"))
            return false;
        if(!uniqueTableVariable(name)) //not unique, already defined
            return false;
        if(validType(name))
            return false;
        if(validEnum(name))
            return false;
        if(validKeyword(name))
            return false;
        return true;
    }

    private static boolean uniqueTableVariable(String name) {
        if(name.equals("$"))
            return false;
        for(Row vars : tableVariables)
            if(name.equals(vars.getName()))
                return false;
        return true;
    }

    private static boolean  validEnum(String name) {
        //TODO: use regex to validate name
        //TODO: make sure name isn't a keyword
        if(name.equals("$"))
            return false;
        for(String tempEnum : enums)
            if(name.equals(tempEnum))
                return true;
        return false;
    }

    private static boolean validType(String name){
        for(String type : types)
            if(name.equals(type))
                return true;
        return false;
    }

    private static boolean validKeyword(String token) {
        for(String key : keywords)
            if(token.equals(key))
                return true;
        return false;
    }
}