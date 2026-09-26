package com.glez.frontendservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Book {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String name;

    @Enumerated(EnumType.STRING)
    private ProcessingStatus status;

    @Lob // Annotation to store large text objects
    private String fullText;

    /**
     * Checkpoint pointer of the pipeline: the step where processing is
     * currently standing (or the last step that was running before a
     * failure/restart). Null when the pipeline never started.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "current_step")
    private ProcessStep currentStep;

    @Column(name = "last_trace_id")
    private String lastTraceId;

    @Column(name = "audio_file_path")
    private String audioFilePath;

    @OneToMany(mappedBy = "book", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Chunks> chunks;
}
