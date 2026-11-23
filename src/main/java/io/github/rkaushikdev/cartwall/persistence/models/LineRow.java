package io.github.rkaushikdev.cartwall.persistence.models;

import static io.github.rkaushikdev.cartwall.persistence.Columns.CODE_LENGTH;

import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.SpotLength;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * One line of an order, with its flight and its copy rotation.
 *
 * <p>The rate is stored on the line and not looked up from the card at read time. That is
 * deliberate duplication: the card can be superseded, the order was written at a number both sides
 * agreed to, and the invoice has to bill what was agreed. {@code card_code} records which card the
 * number came off so anybody can check the arithmetic later.
 */
@Entity
@Table(
        name = "sales_line",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_sales_line_order_number_number",
                        columnNames = {"order_number", "number"}))
public class LineRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "order_number", length = 24, nullable = false)
    private String orderNumber;

    @Column(name = "number", nullable = false)
    private int number;

    @Column(name = "station", length = CODE_LENGTH, nullable = false)
    private String station;

    @Column(name = "daypart", length = CODE_LENGTH, nullable = false)
    private String daypart;

    @Column(name = "grade", length = 32, nullable = false)
    private ClassOfTime grade;

    @Column(name = "length", nullable = false)
    private SpotLength length;

    @Column(name = "rate", nullable = false)
    private Money rate;

    /** Which card the rate came off, so the number can be checked later. */
    @Column(name = "card_code", length = CODE_LENGTH, nullable = false)
    private String cardCode = "";

    @Column(name = "first_air", nullable = false)
    private LocalDate firstAir;

    @Column(name = "last_air", nullable = false)
    private LocalDate lastAir;

    /** {@code M-F}, {@code Sa,Su}: stored the way it was sold. */
    @Column(name = "days", length = 32, nullable = false)
    private String days;

    @Column(name = "per_week", nullable = false)
    private int perWeek;

    /** The Mondays of the weeks the flight goes dark, comma separated; empty when it never does. */
    @Column(name = "dark_weeks", length = 400, nullable = false)
    private String darkWeeks = "";

    @OneToMany(mappedBy = "line", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<RotationRow> rotation = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "order_number",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_sales_line_order_number_sales_order"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private OrderRow order;

    /** Declares the foreign key on {@code station}. Written through the code column. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "station",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_sales_line_station_station"))
    private StationRow signal;

    protected LineRow() {}

    public LineRow(
            int number,
            String station,
            String daypart,
            ClassOfTime grade,
            SpotLength length,
            Money rate,
            LocalDate firstAir,
            LocalDate lastAir,
            String days,
            int perWeek) {
        this.number = number;
        this.station = station;
        this.daypart = daypart;
        this.grade = grade;
        this.length = length;
        this.rate = rate;
        this.firstAir = firstAir;
        this.lastAir = lastAir;
        this.days = days;
        this.perWeek = perWeek;
    }

    void placeOn(OrderRow owner) {
        this.orderNumber = owner.getNumber();
        this.order = owner;
    }

    public Integer getId() {
        return id;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public OrderRow getOrder() {
        return order;
    }

    public int getNumber() {
        return number;
    }

    public String getStation() {
        return station;
    }

    public String getDaypart() {
        return daypart;
    }

    public ClassOfTime getGrade() {
        return grade;
    }

    public SpotLength getLength() {
        return length;
    }

    public Money getRate() {
        return rate;
    }

    public String getCardCode() {
        return cardCode;
    }

    public void setCardCode(String cardCode) {
        this.cardCode = cardCode;
    }

    public LocalDate getFirstAir() {
        return firstAir;
    }

    public LocalDate getLastAir() {
        return lastAir;
    }

    public String getDays() {
        return days;
    }

    public int getPerWeek() {
        return perWeek;
    }

    public String getDarkWeeks() {
        return darkWeeks;
    }

    public void setDarkWeeks(String darkWeeks) {
        this.darkWeeks = darkWeeks;
    }

    /** The rotation, in written order once read back. */
    public List<RotationRow> getRotation() {
        return rotation;
    }

    /** Replace the rotation, each cart pointed back at this line. */
    public void setRotation(List<RotationRow> rotation) {
        this.rotation.clear();
        for (RotationRow row : rotation) {
            row.setLine(this);
            this.rotation.add(row);
        }
    }

    StationRow getSignal() {
        return signal;
    }

    @Override
    public String toString() {
        return "<LineRow " + orderNumber + "-" + number + ">";
    }
}
