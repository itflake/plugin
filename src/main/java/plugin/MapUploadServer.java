package plugin;

import arc.files.Fi;
import fi.iki.elonen.NanoHTTPD;

import java.io.*;
import java.util.*;
import mindustry.*;

public class MapUploadServer extends NanoHTTPD {
    private final File mapDir;
    private final String title;


    public MapUploadServer(String title, int port) throws IOException {
        super(port);
        this.title = title;
        this.mapDir = new File("config/maps");
        if (!mapDir.exists()) {
            throw new IOException("Map folder 'config/maps' does not exist!");
        }

        if (!mapDir.isDirectory()) {
            throw new IOException("Path 'config/maps' is not a directory!");
        }
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        System.out.println("[MapUpload] Web server started on port " + port + ".");
    }

    @Override
    public Response serve(IHTTPSession session) {
        Map<String, List<String>> params = session.getParameters();
        String token = params.getOrDefault("token", List.of("")).get(0);

        if (!TokenManager.isValid(token)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Forbidden or expired token.");
        }
        String uri = session.getUri();
        if (Method.GET.equals(session.getMethod()) && uri.equals("/download")) {
            String fileName = params.getOrDefault("file", List.of("")).get(0);
            if (!fileName.endsWith(".msav")) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid file.");
            }

            File file = new File(mapDir, fileName);
            if (!file.exists()) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found.");
            }

            try {
                FileInputStream fis = new FileInputStream(file);
                return newChunkedResponse(Response.Status.OK, "application/octet-stream", fis);
            } catch (IOException e) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Download error.");
            }
        }
        if (Method.GET.equals(session.getMethod())) {
            StringBuilder html = new StringBuilder("<html><body>");
            html.append("<h2>").append(title).append("</h2>")
                    .append("<h2>Upload Map</h2>")
                    .append("<form method='POST' enctype='multipart/form-data'>")
                    .append("<input type='file' name='file' accept='.msav' required /><br/><br/>")
                    .append("<input type='text' name='confirm' placeholder='type filename to confirm' required /><br/><br/>")
                    .append("<input type='submit' value='Upload' /></form>");

            html.append("<h3>Uploaded Maps:</h3><ul>");
            for (mindustry.maps.Map map : Vars.maps.customMaps()) {
                Fi file = map.file;
                String fileName = file.name();
                String mapName = map.name();

                html.append("<li>")
                        .append("<a href='/download?file=").append(fileName).append("&token=").append(token).append("'>")
                        .append(fileName).append("</a>")
                        .append(" - ").append(mapName)
                        .append("</li>");
            }

            return newFixedLengthResponse(html.toString());
        }

        if (Method.POST.equals(session.getMethod())) {
            try {
                Map<String, String> files = new HashMap<>();
                session.parseBody(files);
                String fileName = session.getParameters().getOrDefault("file", List.of("")).get(0);
                String confirm = params.getOrDefault("confirm", List.of("")).get(0);
                if (fileName == null || confirm == null) return newFixedLengthResponse("No file uploaded or confirmation text missing.");

                File uploadedFile = new File(files.get("file"));
                String safeName = new File(fileName).getName();

                if (safeName.length() > 30) {
                    return newFixedLengthResponse("Filename must be 15 characters or less.");
                }

                if (uploadedFile.length() > 80 * 1024) {
                    return newFixedLengthResponse("File too large. Must be less than 80 KB.");
                }

                if (!safeName.endsWith(".msav") || !safeName.matches("^[A-Za-z0-9_-]+\\.msav$")) {
                    return newFixedLengthResponse("Invalid filename. Only English letters, numbers, -, _ allowed, no spaces, must end with .msav");
                }

                if (!confirm.equals(safeName)) {
                    return newFixedLengthResponse("Enter the filename to upload, e.g., slug.msav");
                }

                File destFile = new File(mapDir, safeName);
                try (InputStream in = new FileInputStream(uploadedFile);
                     OutputStream out = new FileOutputStream(destFile)) {
                    in.transferTo(out);
                }

                String ip = session.getRemoteIpAddress();
                String timestamp = new Date().toString();
                File recordFile = new File("config/mapRecord.txt");
                try (FileWriter writer = new FileWriter(recordFile, true)) {
                    writer.write(timestamp + " - " + safeName + " - " + ip + System.lineSeparator());
                }
                Vars.maps.reload();

                return newFixedLengthResponse("<html><body>Upload success. <a href='?token=" + token + "'>Back</a></body></html>");
            } catch (Exception e) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Upload failed.");
            }
        }

        return newFixedLengthResponse("Unsupported request.");
    }


}
