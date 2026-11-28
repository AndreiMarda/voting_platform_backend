package nord.cluj.voting.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One admin-controlled switch, e.g. {@code registration_open = true}. */
@Entity
@Table(name = "app_settings")
public class AppSetting {

    @Id
    @Column(length = 50)
    private String name;

    @Column(nullable = false)
    private String value;

    protected AppSetting() {
    }

    public AppSetting(String name, String value) {
        this.name = name;
        this.value = value;
    }

    public String getName() { return name; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}
