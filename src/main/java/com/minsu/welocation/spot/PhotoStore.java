package com.minsu.welocation.spot;

import com.minsu.welocation.common.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

// Photos admins upload for the spots they add. Every upload is decoded and written again as a new JPEG, so
// nothing of the original file survives: no EXIF (a phone photo carries the GPS position of whoever took
// it), no trailing data, no second format hidden in the bytes. Files get random names and never change.
@Component
public class PhotoStore {

    public static final String URL_PREFIX = "/photos/";
    static final int MAX_BYTES = 5 * 1024 * 1024;
    // A 1600 px side is plenty for the gallery. The size is read from the header before decoding, and the
    // decoder then skips pixels so it never holds much more than 1600 px a side: a 48 MP phone photo at full
    // size would be 144 MB on a 256 MB heap. One decode at a time, for the same reason.
    static final int MAX_SIDE = 1600;
    private static final int MAX_SOURCE_SIDE = 8000;
    private static final Semaphore DECODING = new Semaphore(1);
    private static final Pattern NAME = Pattern.compile("[0-9a-f]{32}\\.jpg");

    private final Path dir;

    PhotoStore(@Value("${welocation.photos.dir}") String dir) {
        this.dir = Path.of(dir).toAbsolutePath().normalize();
    }

    // returns the URL the spot stores, /photos/<name>
    public String save(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new BadRequestException("photo: empty or larger than 5 MB");
        }
        BufferedImage image;
        DECODING.acquireUninterruptibly();
        try {
            image = decode(bytes);
        } finally {
            DECODING.release();
        }
        String name = UUID.randomUUID().toString().replace("-", "") + ".jpg";
        try {
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "upload-", ".tmp");
            try {
                writeJpeg(scaled(image), tmp);
                Files.move(tmp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not store photo", e);
        }
        return URL_PREFIX + name;
    }

    // empty for anything that isn't one of our file names, so a request can't walk out of the folder
    public Optional<Path> find(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            return Optional.empty();
        }
        Path file = dir.resolve(name);
        return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }

    public void delete(String url) {
        if (url == null || !url.startsWith(URL_PREFIX)) {
            return;
        }
        find(url.substring(URL_PREFIX.length())).ifPresent(file -> {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                throw new UncheckedIOException("could not delete photo", e);
            }
        });
    }

    public static boolean isOurs(String url) {
        return url != null && url.startsWith(URL_PREFIX) && NAME.matcher(url.substring(URL_PREFIX.length())).matches();
    }

    // JPEG and PNG only: what ImageIO reads without plugins, and what the browser sends after it shrinks a photo
    private static BufferedImage decode(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new BadRequestException("photo: not a JPEG or PNG image");
            }
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase();
                if (!format.equals("jpeg") && !format.equals("png")) {
                    throw new BadRequestException("photo: not a JPEG or PNG image");
                }
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_SOURCE_SIDE || height > MAX_SOURCE_SIDE) {
                    throw new BadRequestException("photo: too large");
                }
                // rounded down: the decoded image stays at least 1600 px and under 3200 px a side, then gets scaled
                int step = Math.max(1, Math.max(width, height) / MAX_SIDE);
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(step, step, 0, 0);
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof BadRequestException bad) {
                throw bad;
            }
            throw new BadRequestException("photo: could not be read");
        }
    }

    // drawn onto a plain RGB canvas: drops transparency (JPEG has none) and any odd colour model
    private static BufferedImage scaled(BufferedImage source) {
        double factor = Math.min(1.0, (double) MAX_SIDE / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * factor));
        int height = Math.max(1, (int) Math.round(source.getHeight() * factor));
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static void writeJpeg(BufferedImage image, Path file) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(file.toFile())) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.85f);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }
}
