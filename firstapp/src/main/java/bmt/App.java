package bmt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import bmt.Models.UsersModel;
import org.mindrot.jbcrypt.BCrypt;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

public class App {
        public static void main(String[] args) throws IOException {
                String sftpHost = "sftp.example.com"; // Host del servidor SFTP
                int sftpPort = 22; // Puerto del servidor SFTP (por defecto es 22)
                String sftpUser = "username"; // Usuario SFTP
                String sftpPassword = "password"; // Contraseña SFTP
                String sftpDir = "/remote/directory";

                String filePath = "firstapp/src/test/resources/config.properties";
                Properties pros;
                pros = new Properties();
                FileInputStream ip = new FileInputStream(filePath);
                pros.load(ip);

                String json = pros.getProperty("json1");
                String etl = pros.getProperty("csv1");
                String summ = pros.getProperty("summcsv");
                createJson(pros);

                JsonToCSVETL(json);

                List<UsersModel> users = readUsersFromFile(json);

                insertUsers(users);
                executeQueriesAndSaveToCSV("dbuser.db");

                insertCSVToDB(summ, "jdbc:sqlite:dbuser.db");

                // subir archivos al servidor sftp
                // uploadFileToSFTP(sftpHost, sftpPort, sftpUser, sftpPassword, sftpDir, json);

                // uploadFileToSFTP(sftpHost, sftpPort, sftpUser, sftpPassword, sftpDir, summ);

                // uploadFileToSFTP(sftpHost, sftpPort, sftpUser, sftpPassword, sftpDir, etl);

        }

        public static void uploadFileToSFTP(String host, int port, String user, String password, String remoteDir,
                        String filePath) {
                Session session = null;
                ChannelSftp channelSftp = null;

                try {
                        // Configurar la sesión SFTP
                        JSch jsch = new JSch();
                        session = jsch.getSession(user, host, port);
                        session.setPassword(password);

                        // Configuración adicional para evitar la verificación de la clave del host (no
                        // recomendado en producción)
                        java.util.Properties config = new java.util.Properties();
                        config.put("StrictHostKeyChecking", "no");
                        session.setConfig(config);

                        // Conectar al servidor
                        session.connect();

                        // Abrir el canal SFTP
                        channelSftp = (ChannelSftp) session.openChannel("sftp");
                        channelSftp.connect();

                        // Cambiar al directorio remoto
                        channelSftp.cd(remoteDir);

                        // Subir el archivo usando la ruta del archivo
                        channelSftp.put(filePath);

                        System.out.println("Archivo subido exitosamente al servidor SFTP.");

                } catch (Exception e) {
                        e.printStackTrace();
                } finally {
                        if (channelSftp != null) {
                                channelSftp.disconnect();
                        }
                        if (session != null) {
                                session.disconnect();
                        }
                }
        }

        protected static String createJson(Properties pros) throws IOException {
                LocalDate currentDate = LocalDate.now();
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
                String formattedDate = currentDate.format(formatter);
                String url = pros.getProperty("url");
                URL usuarios = new URL(url);

                HttpURLConnection cx = (HttpURLConnection) usuarios.openConnection();
                cx.setRequestMethod("GET");

                InputStream strm = cx.getInputStream();
                byte[] arrstr = strm.readAllBytes();

                String cntjson = "";

                // Ruta del archivo en el directorio src/test/resources
                String fileName = "firstapp/src/test/resources/data_" + formattedDate + ".json";
                File file = new File(fileName);
                file.getParentFile().mkdirs(); // Crear directorios si no existen

                try (FileWriter filewriter = new FileWriter(file)) {
                        for (byte tmp : arrstr) {
                                cntjson += (char) tmp;
                        }

                        filewriter.write(cntjson);

                } catch (Exception e) {
                        e.printStackTrace();
                }

                return fileName;
        }

        public static void JsonToCSVETL(String jsonFilePath) throws IOException {
                // Leer el archivo JSON
                ObjectMapper objectMapper = new ObjectMapper();
                JsonNode jsonTree = objectMapper.readTree(new File(jsonFilePath));

                // Acceder al array de usuarios
                JsonNode usersNode = jsonTree.path("users");
                if (usersNode.isMissingNode() || !usersNode.isArray()) {
                        throw new IOException("El archivo JSON no contiene un array 'users' válido.");
                }

                // Crear el esquema CSV automáticamente basado en los campos del primer objeto
                // del array 'users'
                CsvSchema.Builder csvSchemaBuilder = CsvSchema.builder();
                Iterator<JsonNode> elements = usersNode.elements();
                if (elements.hasNext()) {
                        JsonNode firstObject = elements.next();
                        Map<String, String> flatMap = new HashMap<>();
                        flattenJson("", firstObject, flatMap);
                        flatMap.keySet().forEach(csvSchemaBuilder::addColumn);
                } else {
                        throw new IOException("El array 'users' está vacío.");
                }
                CsvSchema csvSchema = csvSchemaBuilder.build().withHeader();

                // Crear el nombre del archivo CSV con la fecha actual
                String date = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
                String csvFileName = "firstapp/src/test/resources/ETL_" + date + ".csv";
                File file = new File(csvFileName);
                file.getParentFile().mkdirs(); // Crear directorios si no existen

                // Aplanar y escribir el archivo CSV
                CsvMapper csvMapper = new CsvMapper();
                List<Map<String, String>> flatData = new ArrayList<>();
                Iterator<JsonNode> userElements = usersNode.elements();
                while (userElements.hasNext()) {
                        JsonNode user = userElements.next();
                        Map<String, String> flatMap = new HashMap<>();
                        flattenJson("", user, flatMap);
                        flatData.add(flatMap);
                }
                csvMapper.writerFor(List.class)
                                .with(csvSchema)
                                .writeValue(file, flatData);
        }

        private static void flattenJson(String prefix, JsonNode node, Map<String, String> flatMap) {
                if (node.isObject()) {
                        node.fieldNames().forEachRemaining(fieldName -> {
                                JsonNode childNode = node.get(fieldName);
                                flattenJson(prefix + fieldName + ".", childNode, flatMap);
                        });
                } else if (node.isArray()) {
                        for (int i = 0; i < node.size(); i++) {
                                JsonNode childNode = node.get(i);
                                flattenJson(prefix + i + ".", childNode, flatMap);
                        }
                } else {
                        flatMap.put(prefix.substring(0, prefix.length() - 1), node.asText());
                }
        }

        public static Connection connect(String URL) {
                Connection conn = null;
                try {
                        // Establecer la conexión
                        conn = DriverManager.getConnection(URL);
                        System.out.println("Conexión a SQLite establecida.");
                } catch (SQLException e) {
                        System.out.println(e.getMessage());
                }
                return conn;
        }

        public static List<UsersModel> readUsersFromFile(String filePath) {
                List<UsersModel> users = new ArrayList<>();
                ObjectMapper objectMapper = new ObjectMapper();
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
                try {
                        JsonNode rootNode = objectMapper.readTree(new File(filePath));
                        for (JsonNode node : rootNode.get("users")) {
                                UsersModel user = new UsersModel();
                                user.setUsers_id(node.has("id") ? node.get("id").asInt() : null);
                                user.setUsers_firstName(node.has("firstName") ? node.get("firstName").asText() : null);
                                user.setUsers_lastName(node.has("lastName") ? node.get("lastName").asText() : null);
                                user.setUsers_maidenName(
                                                node.has("maidenName") ? node.get("maidenName").asText() : null);
                                user.setUsers_age(node.has("age") ? node.get("age").asInt() : null);
                                user.setUsers_gender(node.has("gender") ? node.get("gender").asText() : null);
                                user.setUsers_email(node.has("email") ? node.get("email").asText() : null);
                                user.setUsers_phone(node.has("phone") ? node.get("phone").asText() : null);
                                user.setUsers_username(node.has("username") ? node.get("username").asText() : null);
                                user.setUsers_password(node.has("password") ? node.get("password").asText() : null);
                                user.setUsers_birthDate(node.has("birthDate") ? node.get("birthDate").asText() : null);
                                user.setUsers_image(node.has("image") ? node.get("image").asText() : null);
                                user.setUsers_bloodGroup(
                                                node.has("bloodGroup") ? node.get("bloodGroup").asText() : null);
                                user.setUsers_height(node.has("height") ? node.get("height").asDouble() : null);
                                user.setUsers_weight(node.has("weight") ? node.get("weight").asDouble() : null);
                                user.setUsers_eyeColor(node.has("eyeColor") ? node.get("eyeColor").asText() : null);
                                user.setUsers_hair_color(
                                                node.get("hair").has("color") ? node.get("hair").get("color").asText()
                                                                : null);
                                user.setUsers_hair_type(
                                                node.get("hair").has("type") ? node.get("hair").get("type").asText()
                                                                : null);
                                user.setUsers_ip(node.has("ip") ? node.get("ip").asText() : null);
                                user.setUsers_address_address(
                                                node.get("address").has("address")
                                                                ? node.get("address").get("address").asText()
                                                                : null);
                                user.setUsers_address_city(
                                                node.get("address").has("city")
                                                                ? node.get("address").get("city").asText()
                                                                : null);
                                user.setUsers_address_state(
                                                node.get("address").has("state")
                                                                ? node.get("address").get("state").asText()
                                                                : null);
                                user.setUsers_address_stateCode(
                                                node.get("address").has("stateCode")
                                                                ? node.get("address").get("stateCode").asText()
                                                                : null);
                                user.setUsers_address_postalCode(
                                                node.get("address").has("postalCode")
                                                                ? node.get("address").get("postalCode").asText()
                                                                : null);
                                user.setUsers_address_coordinates_lat(
                                                node.get("address").get("coordinates").has("lat")
                                                                ? node.get("address").get("coordinates").get("lat")
                                                                                .asText()
                                                                : null);
                                user.setUsers_address_coordinates_lng(
                                                node.get("address").get("coordinates").has("lng")
                                                                ? node.get("address").get("coordinates").get("lng")
                                                                                .asText()
                                                                : null);
                                user.setUsers_address_country(
                                                node.get("address").has("country")
                                                                ? node.get("address").get("country").asText()
                                                                : null);
                                user.setUsers_macAddress(
                                                node.has("macAddress") ? node.get("macAddress").asText() : null);
                                user.setUsers_university(
                                                node.has("university") ? node.get("university").asText() : null);
                                user.setUsers_bank_cardExpire(
                                                node.get("bank").has("cardExpire")
                                                                ? node.get("bank").get("cardExpire").asText()
                                                                : null);
                                user.setUsers_bank_cardNumber(
                                                node.get("bank").has("cardNumber")
                                                                ? node.get("bank").get("cardNumber").asText()
                                                                : null);
                                user.setUsers_bank_cardType(
                                                node.get("bank").has("cardType")
                                                                ? node.get("bank").get("cardType").asText()
                                                                : null);
                                user.setUsers_bank_currency(
                                                node.get("bank").has("currency")
                                                                ? node.get("bank").get("currency").asText()
                                                                : null);
                                user.setUsers_bank_iban(
                                                node.get("bank").has("iban") ? node.get("bank").get("iban").asText()
                                                                : null);
                                user.setUsers_company_department(
                                                node.get("company").has("department")
                                                                ? node.get("company").get("department").asText()
                                                                : null);
                                user.setUsers_company_name(
                                                node.get("company").has("name")
                                                                ? node.get("company").get("name").asText()
                                                                : null);
                                user.setUsers_company_title(
                                                node.get("company").has("title")
                                                                ? node.get("company").get("title").asText()
                                                                : null);
                                user.setUsers_company_address_address(
                                                node.get("company").get("address").has("address")
                                                                ? node.get("company").get("address").get("address")
                                                                                .asText()
                                                                : null);
                                user.setUsers_company_address_city(
                                                node.get("company").get("address").has("city")
                                                                ? node.get("company").get("address").get("city")
                                                                                .asText()
                                                                : null);
                                user.setUsers_company_address_state(
                                                node.get("company").get("address").has("state")
                                                                ? node.get("company").get("address").get("state")
                                                                                .asText()
                                                                : null);
                                user.setUsers_company_address_stateCode(
                                                node.get("company").get("address").has("stateCode")
                                                                ? node.get("company").get("address").get("stateCode")
                                                                                .asText()
                                                                : null);
                                user.setUsers_company_address_postalCode(
                                                node.get("company").get("address").has("postalCode")
                                                                ? node.get("company").get("address").get("postalCode")
                                                                                .asText()
                                                                : null);
                                user.setUsers_company_address_coordinates_lat(
                                                node.get("company").get("address").get("coordinates").has("lat")
                                                                ? node.get("company").get("address").get("coordinates")
                                                                                .get("lat").asText()
                                                                : null);
                                user.setUsers_company_address_coordinates_lng(
                                                node.get("company").get("address").get("coordinates").has("lng")
                                                                ? node.get("company").get("address").get("coordinates")
                                                                                .get("lng").asText()
                                                                : null);
                                user.setUsers_company_address_country(
                                                node.get("company").get("address").has("country")
                                                                ? node.get("company").get("address").get("country")
                                                                                .asText()
                                                                : null);
                                user.setUsers_ein(node.has("ein") ? node.get("ein").asText() : null);
                                user.setUsers_ssn(node.has("ssn") ? node.get("ssn").asText() : null);
                                user.setUsers_userAgent(node.has("userAgent") ? node.get("userAgent").asText() : null);
                                user.setUsers_crypto_coin(
                                                node.get("crypto").has("coin") ? node.get("crypto").get("coin").asText()
                                                                : null);
                                user.setUsers_crypto_wallet(
                                                node.get("crypto").has("wallet")
                                                                ? node.get("crypto").get("wallet").asText()
                                                                : null);
                                user.setUsers_crypto_network(
                                                node.get("crypto").has("network")
                                                                ? node.get("crypto").get("network").asText()
                                                                : null);
                                user.setUsers_role(node.has("role") ? node.get("role").asText() : null);
                                user.setDate_insertion(LocalDate.now().format(formatter));

                                users.add(user);
                        }
                } catch (IOException e) {
                        e.printStackTrace();
                }

                return users;
        }

        public static void insertUsers(List<UsersModel> users) {
                String sql = "INSERT INTO users("
                                + "users_id, users_firstName, users_lastName, users_maidenName, users_age, users_gender, "
                                + "users_email, users_phone, users_username, users_password, users_birthDate, users_image, "
                                + "users_bloodGroup, users_height, users_weight, users_eyeColor, users_hair_color, users_hair_type, "
                                + "users_ip, users_address_address, users_address_city, users_address_state, users_address_stateCode, "
                                + "users_address_postalCode, users_address_coordinates_lat, users_address_coordinates_lng, "
                                + "users_address_country, users_macAddress, users_university, users_bank_cardExpire, "
                                + "users_bank_cardNumber, users_bank_cardType, users_bank_currency, users_bank_iban, "
                                + "users_company_department, users_company_name, users_company_title, users_company_address_address, "
                                + "users_company_address_city, users_company_address_state, users_company_address_stateCode, "
                                + "users_company_address_postalCode, users_company_address_coordinates_lat, users_company_address_coordinates_lng, "
                                + "users_company_address_country, users_ein, users_ssn, users_userAgent, users_crypto_coin, "
                                + "users_crypto_wallet, users_crypto_network, users_role, date_insertion) "
                                + "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,?,?,?,?,?)";

                try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + "dbuser.db");
                                PreparedStatement pstmt = conn.prepareStatement(sql)) {
                        for (UsersModel user : users) {

                                // Encriptar la contraseña utilizando BCrypt
                                String hashedPassword = BCrypt.hashpw(user.getUsers_password(), BCrypt.gensalt());

                                pstmt.setInt(1, user.getUsers_id());
                                pstmt.setString(2, user.getUsers_firstName());
                                pstmt.setString(3, user.getUsers_lastName());
                                pstmt.setString(4, user.getUsers_maidenName());
                                pstmt.setInt(5, user.getUsers_age());
                                pstmt.setString(6, user.getUsers_gender());
                                pstmt.setString(7, user.getUsers_email());
                                pstmt.setString(8, user.getUsers_phone());
                                pstmt.setString(9, user.getUsers_username());

                                // Insertar la contraseña encriptada
                                pstmt.setString(10, hashedPassword);

                                pstmt.setString(11, user.getUsers_birthDate());
                                pstmt.setString(12, user.getUsers_image());
                                pstmt.setString(13, user.getUsers_bloodGroup());
                                pstmt.setDouble(14, user.getUsers_height());
                                pstmt.setDouble(15, user.getUsers_weight());
                                pstmt.setString(16, user.getUsers_eyeColor());
                                pstmt.setString(17, user.getUsers_hair_color());
                                pstmt.setString(18, user.getUsers_hair_type());
                                pstmt.setString(19, user.getUsers_ip());
                                pstmt.setString(20, user.getUsers_address_address());
                                pstmt.setString(21, user.getUsers_address_city());
                                pstmt.setString(22, user.getUsers_address_state());
                                pstmt.setString(23, user.getUsers_address_stateCode());
                                pstmt.setString(24, user.getUsers_address_postalCode());
                                pstmt.setString(25, user.getUsers_address_coordinates_lat());
                                pstmt.setString(26, user.getUsers_address_coordinates_lng());
                                pstmt.setString(27, user.getUsers_address_country());
                                pstmt.setString(28, user.getUsers_macAddress());
                                pstmt.setString(29, user.getUsers_university());
                                pstmt.setString(30, user.getUsers_bank_cardExpire());
                                pstmt.setString(31, user.getUsers_bank_cardNumber());
                                pstmt.setString(32, user.getUsers_bank_cardType());
                                pstmt.setString(33, user.getUsers_bank_currency());
                                pstmt.setString(34, user.getUsers_bank_iban());
                                pstmt.setString(35, user.getUsers_company_department());
                                pstmt.setString(36, user.getUsers_company_name());
                                pstmt.setString(37, user.getUsers_company_title());
                                pstmt.setString(38, user.getUsers_company_address_address());
                                pstmt.setString(39, user.getUsers_company_address_city());
                                pstmt.setString(40, user.getUsers_company_address_state());
                                pstmt.setString(41, user.getUsers_company_address_stateCode());
                                pstmt.setString(42, user.getUsers_company_address_postalCode());
                                pstmt.setString(43, user.getUsers_company_address_coordinates_lat());
                                pstmt.setString(44, user.getUsers_company_address_coordinates_lng());
                                pstmt.setString(45, user.getUsers_company_address_country());
                                pstmt.setString(46, user.getUsers_ein());
                                pstmt.setString(47, user.getUsers_ssn());
                                pstmt.setString(48, user.getUsers_userAgent());
                                pstmt.setString(49, user.getUsers_crypto_coin());
                                pstmt.setString(50, user.getUsers_crypto_wallet());
                                pstmt.setString(51, user.getUsers_crypto_network());
                                pstmt.setString(52, user.getUsers_role());
                                pstmt.setString(53, user.getDate_insertion());
                                pstmt.executeUpdate();
                        }
                } catch (SQLException e) {
                        System.out.println(e.getMessage());
                }
        }

        public static void executeQueriesAndSaveToCSV(String dbPath) {
                String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                String csvFilename = "firstapp/src/test/resources/summary_" + date + ".csv";

                String[] queries = {
                                "SELECT count(*) AS 'registers' FROM users;",
                                "SELECT users_gender, COUNT(*) AS Total FROM users WHERE users_gender IN ('male', 'female', 'other') GROUP BY users_gender UNION SELECT 'male' AS users_gender, 0 AS Total WHERE NOT EXISTS (SELECT 1 FROM users WHERE users_gender = 'male') UNION SELECT 'female' AS users_gender, 0 AS Total WHERE NOT EXISTS (SELECT 1 FROM users WHERE users_gender = 'female') UNION SELECT 'other' AS users_gender, 0 AS Total WHERE NOT EXISTS (SELECT 1 FROM users WHERE users_gender = 'other');",
                                "SELECT CASE WHEN users_age BETWEEN 0 AND 10 THEN '00-10' WHEN users_age BETWEEN 11 AND 20 THEN '11-20' WHEN users_age BETWEEN 21 AND 30 THEN '21-30' WHEN users_age BETWEEN 31 AND 40 THEN '31-40' WHEN users_age BETWEEN 41 AND 50 THEN '41-50' WHEN users_age BETWEEN 51 AND 60 THEN '51-60' WHEN users_age BETWEEN 61 AND 70 THEN '61-70' WHEN users_age BETWEEN 71 AND 80 THEN '71-80' WHEN users_age BETWEEN 81 AND 90 THEN '81-90' ELSE '91+' END AS AgeRange, users_gender, COUNT(*) AS Total FROM users GROUP BY AgeRange, users_gender ORDER BY AgeRange ASC;",
                                "SELECT users_address_city AS city, SUM(CASE WHEN users_gender = 'male' THEN 1 ELSE 0 END) AS male, SUM(CASE WHEN users_gender = 'female' THEN 1 ELSE 0 END) AS female, SUM(CASE WHEN users_gender = 'other' THEN 1 ELSE 0 END) AS other FROM users GROUP BY users_address_city ORDER BY users_address_city ASC;",
                                "SELECT CASE WHEN users_userAgent LIKE '%Windows NT%' THEN 'Windows NT' WHEN users_userAgent LIKE '%Windows%' THEN 'Windows' WHEN users_userAgent LIKE '%Macintosh%' THEN 'Macintosh' WHEN users_userAgent LIKE '%Linux%' THEN 'Linux' WHEN users_userAgent LIKE '%Android%' THEN 'Android' WHEN users_userAgent LIKE '%iPhone%' THEN 'iPhone' WHEN users_userAgent LIKE '%iPad%' THEN 'iPad' ELSE 'Other' END AS SO, COUNT(*) AS Total FROM users GROUP BY SO ORDER BY Total DESC;"
                };

                try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                                FileWriter csvWriter = new FileWriter(csvFilename)) {

                        for (String query : queries) {
                                try (Statement stmt = conn.createStatement();
                                                ResultSet rs = stmt.executeQuery(query)) {

                                        int columnCount = rs.getMetaData().getColumnCount();
                                        for (int i = 1; i <= columnCount; i++) {
                                                csvWriter.append(rs.getMetaData().getColumnName(i));
                                                if (i < columnCount)
                                                        csvWriter.append(",");
                                        }
                                        csvWriter.append("\n");

                                        while (rs.next()) {
                                                for (int i = 1; i <= columnCount; i++) {
                                                        csvWriter.append(rs.getString(i));
                                                        if (i < columnCount)
                                                                csvWriter.append(",");
                                                }
                                                csvWriter.append("\n");
                                        }
                                        csvWriter.append("\n"); // Separate results of different queries
                                }
                        }

                } catch (SQLException | IOException e) {
                        e.printStackTrace();
                }
        }

        public static void insertCSVToDB(String csvFilePath, String dbUrl) {
                String insertSQL = "INSERT INTO summary (registers, date_insert) VALUES (?, ?)";

                try (Connection conn = DriverManager.getConnection(dbUrl);
                                PreparedStatement insertStmt = conn.prepareStatement(insertSQL);
                                BufferedReader br = new BufferedReader(new FileReader(csvFilePath))) {

                        // Obtener la fecha actual en el formato yyyy-MM-dd
                        String dateInsert = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

                        String line;
                        while ((line = br.readLine()) != null) {
                                // Insertar cada línea del CSV y la fecha de inserción en la base de datos
                                insertStmt.setString(1, line);
                                insertStmt.setString(2, dateInsert);
                                insertStmt.executeUpdate();
                        }

                } catch (SQLException | IOException e) {
                        System.out.println(e.getMessage());
                }
        }

}