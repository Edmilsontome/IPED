package dpf.mg.udi.gpinf.whatsappextractor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.codec.binary.Hex;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.params.KeyParameter;

import com.whatsapp.MediaData;

/**
 *
 * @author PCF HAUCK
 */
public class LinkExtractor {
    private Connection con;
    private HashSet<String> hashes;
    private ArrayList<LinkDownloader> links;
    private String folder;

    public LinkExtractor(File dbPath, HashSet<String> hashes) {
        this.hashes = hashes;
        this.con = createConnection(dbPath.getAbsolutePath());
        links = new ArrayList<>();
    }

    public Connection createConnection(String dbname) {
        try {
            return DriverManager.getConnection("jdbc:sqlite:" + dbname);
        } catch (Exception ex) {
            System.err.println(ex.toString());
        }

        return null;
    }

    public void getKeyFromMediaKey(byte[] mediaKey) {

    }

    public static int tot = 0;

    private class HKDF {
        private HMac hMacHash = new HMac(new SHA256Digest());

        public byte[] extract(byte[] salt, byte[] inputKeyMaterial) {
            hMacHash.init(new KeyParameter(salt));
            byte[] output = new byte[32];

            hMacHash.update(inputKeyMaterial, 0, inputKeyMaterial.length);
            hMacHash.doFinal(output, 0);

            return output;
        }

        public byte[] expand(byte[] prk, byte[] info, int outputSize) {
            int iterations = (int) Math.ceil((double) outputSize / (double) 32);
            byte[] mixin = new byte[0];
            ByteArrayOutputStream results = new ByteArrayOutputStream();
            int remainingBytes = outputSize;

            for (int i = 1; i <= iterations; i++) {

                hMacHash.init(new KeyParameter(prk));

                byte[] stepResult = new byte[32];

                hMacHash.update(mixin, 0, mixin.length);

                hMacHash.update(info, 0, info.length);

                hMacHash.update((byte) i);

                hMacHash.doFinal(stepResult, 0);

                int stepSize = Math.min(remainingBytes, stepResult.length);

                results.write(stepResult, 0, stepSize);

                mixin = stepResult;
                remainingBytes -= stepSize;
            }

            return results.toByteArray();
        }
    }

    public byte[] getCipherKey(byte[] rawData) {

        try {
            ByteArrayInputStream bis = new ByteArrayInputStream(rawData);
            ObjectInput in = new ObjectInputStream(bis);
            MediaData media = (MediaData) in.readObject();

            if (media.cipherKey != null)
                return media.cipherKey;
            else {
                HKDF hkg = new HKDF();

                byte[] key = hkg.expand(hkg.extract(new byte[32], media.mediaKey), aux.getBytes("UTF-8"), 112);

                byte[] cpk = Arrays.copyOfRange(key, 16, 48);

                return cpk;
            }
        } catch (Exception ex) {
            Logger.getLogger(LinkExtractor.class.getName()).log(Level.SEVERE, null, ex);
        }
        return null;

    }

    public byte[] getIV(byte[] rawData) {
        try {
            ByteArrayInputStream bis = new ByteArrayInputStream(rawData);
            ObjectInput in = new ObjectInputStream(bis);
            MediaData media = (MediaData) in.readObject();
            if (media.iv != null) {
                return media.iv;
            } else {
                HKDF hkg = new HKDF();

                byte[] key = hkg.expand(hkg.extract(new byte[32], media.mediaKey), aux.getBytes("UTF-8"), 112);

                byte[] iv = Arrays.copyOfRange(key, 0, 16);

                return iv;
            }
        } catch (Exception ex) {
            Logger.getLogger(LinkExtractor.class.getName()).log(Level.SEVERE, null, ex);
        }
        return null;
    }

    private String aux = "";

    public static String capitalize(String aux) {
        String temp = aux = aux.substring(0, 1).toUpperCase() + aux.substring(1);
        return temp;
    }

    public void extractLinks() {

        if (con == null) {
            return;
        }

        try {
            
            
            StringBuilder base64Hashes=null;
            for (String hash : hashes) {
                hash = Base64.getEncoder().encodeToString(Hex.decodeHex(hash));

                if (base64Hashes == null) {
                    base64Hashes = new StringBuilder();
                } else {
                    base64Hashes.append(",");

                }
                base64Hashes.append('"');
                base64Hashes.append(hash);
                base64Hashes.append('"');
            }
            PreparedStatement stmt = con.prepareStatement(sql_android.replaceAll("\\?", base64Hashes.toString()));
            // stmt.setCharacterStream(1, new StringReader(base64Hashes.toString()));

            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                String link = rs.getString("url");
                String hash = rs.getString("hash");

                String decoded = new String(Hex.encodeHex(Base64.getDecoder().decode(hash), false));
                if (!hashes.contains(decoded)) {
                    continue;
                }
                String tipo = rs.getString("tipo");
                long id = rs.getLong("_id");
                if (tipo == null) {
                    continue;
                }
                aux = tipo.substring(0, tipo.indexOf("/"));
                aux = capitalize(aux).trim();
                aux = "WhatsApp " + aux + " Keys";

                tipo = tipo.substring(tipo.indexOf("/") + 1);

                byte[] rawData = rs.getBytes("data");
                byte[] cipherkey = getCipherKey(rawData);
                byte[] iv = getIV(rawData);
                if (cipherkey == null || iv == null) {
                    tot++;
                }

                LinkDownloader ld = new LinkDownloader(link, tipo, hash, cipherkey, iv);
                if (ld.getFileName() != null)
                    links.add(ld);
            }
        } catch (Exception e) {
            e.printStackTrace(System.out);
        }
    }

    public ArrayList<LinkDownloader> getLinks() {
        return links;
    }

    public static final String sql_android = "SELECT media_url as url,media_hash as hash ,media_mime_type as tipo,thumb_image as data,_id from messages "
            + "where media_url is not null and media_hash is not null and media_hash in (?) group by url";

}